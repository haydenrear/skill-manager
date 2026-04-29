package dev.skillmanager.server;

import dev.skillmanager.registry.dto.ListResponse;
import dev.skillmanager.registry.dto.PublishResponse;
import dev.skillmanager.registry.dto.SearchResponse;
import dev.skillmanager.registry.dto.SkillSummary;
import dev.skillmanager.registry.dto.SkillVersion;
import dev.skillmanager.server.persistence.ConversionRepository;
import dev.skillmanager.server.persistence.ConversionRow;
import dev.skillmanager.server.publish.PublishException;
import dev.skillmanager.server.publish.SkillPublishService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
public class SkillRegistryController {

    private final SkillStorage storage;
    private final AdMatcher adMatcher;
    private final ConversionRepository conversions;
    private final SkillPublishService publishService;
    private final boolean allowFileUpload;

    public SkillRegistryController(SkillStorage storage, AdMatcher adMatcher,
                                   ConversionRepository conversions,
                                   SkillPublishService publishService,
                                   @Value("${skill-registry.publish.allow-file-upload:false}") boolean allowFileUpload) {
        this.storage = storage;
        this.adMatcher = adMatcher;
        this.conversions = conversions;
        this.publishService = publishService;
        this.allowFileUpload = allowFileUpload;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "root", storage.root().toString());
    }

    @GetMapping("/skills")
    public ListResponse list() throws IOException {
        List<SkillSummary> items = storage.listAll();
        return new ListResponse(items, items.size());
    }

    @GetMapping("/skills/search")
    public SearchResponse search(
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "no_ads", defaultValue = "false") boolean noAds,
            @RequestParam(value = "sponsored_limit", defaultValue = "3") int sponsoredLimit,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        // Organic lane is computed independently of any campaign data.
        List<SkillSummary> hits = storage.search(q, Math.max(1, Math.min(limit, 100)));
        // Sponsored lane lives in a separate array; caller can opt out.
        String viewer = jwt == null ? null : jwt.getSubject();
        List<dev.skillmanager.registry.dto.SponsoredPlacement> sponsored = noAds
                ? List.of()
                : adMatcher.match(q, sponsoredLimit, viewer);
        return new SearchResponse(q, hits, hits.size(), sponsored, sponsored.size());
    }

    @GetMapping("/skills/{name}")
    public SkillSummary describe(@PathVariable String name) throws IOException {
        return storage.describe(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill not found: " + name));
    }

    @GetMapping("/skills/{name}/{version}")
    public SkillVersion describeVersion(@PathVariable String name, @PathVariable String version) throws IOException {
        String v = resolveVersion(name, version);
        return storage.describeVersion(name, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "version not found: " + name + "@" + v));
    }

    @GetMapping("/skills/{name}/{version}/download")
    public ResponseEntity<Resource> download(
            @PathVariable String name,
            @PathVariable String version,
            @RequestParam(value = "campaign_id", required = false) String campaignId,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        String v = resolveVersion(name, version);
        Path path = storage.packagePath(name, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "package not found: " + name + "@" + v));
        // Attribution: the client attaches ?campaign_id=<id> when the install
        // was prompted by a sponsored placement. Anonymous downloads still
        // record a conversion row — installer_login is null.
        if (campaignId != null && !campaignId.isBlank()) {
            String installer = jwt == null ? null : jwt.getSubject();
            conversions.save(new ConversionRow(campaignId, name, v, installer));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + v + ".tar.gz\"")
                .body(new FileSystemResource(path));
    }

    /**
     * GitHub-pointer publish — the default path going forward. Body:
     * {@code {"github_url": "https://github.com/owner/repo", "git_ref": "v0.1.0"}}.
     * Server fetches the toml + SKILL.md at that ref, derives the skill name
     * + version from the toml (defaulting to {@code 0.0.1} if absent), and
     * persists a metadata-only row pointing at the resolved commit SHA.
     */
    @PostMapping("/skills/register")
    public ResponseEntity<SkillVersion> register(
            @RequestBody RegisterRequest body,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "register requires a bearer token");
        }
        if (body == null || body.githubUrl() == null || body.githubUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "github_url is required");
        }
        SkillVersion record;
        try {
            record = publishService.registerFromGithub(body.githubUrl(), body.gitRef(), jwt.getSubject());
        } catch (PublishException.BadVersion e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        } catch (PublishException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (PublishException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }

    public record RegisterRequest(String githubUrl, String gitRef) {}

    @PostMapping("/skills/{name}/{version}")
    public ResponseEntity<PublishResponse> publish(
            @PathVariable String name,
            @PathVariable String version,
            @RequestParam("package") MultipartFile pkg,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        if (!allowFileUpload) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "file-upload publish is disabled on this server. Use POST /skills/register with a github_url, "
                            + "or set skill-registry.publish.allow-file-upload=true to re-enable the legacy backend.");
        }
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "publish requires a bearer token");
        }
        byte[] bytes = pkg.getBytes();
        SkillVersion record;
        try {
            record = publishService.publish(name, version, bytes, jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (PublishException.BadVersion e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (PublishException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (PublishException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        PublishResponse out = new PublishResponse(
                record.name(),
                record.version(),
                record.sha256(),
                record.sizeBytes() == null ? 0L : record.sizeBytes(),
                "/skills/" + record.name() + "/" + record.version() + "/download");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @DeleteMapping("/skills/{name}")
    public Map<String, Object> deleteSkill(@PathVariable String name, @AuthenticationPrincipal Jwt jwt) throws IOException {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "delete requires a bearer token");
        try {
            if (!publishService.deleteSkill(name, jwt.getSubject())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill not found: " + name);
            }
        } catch (PublishException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        return Map.of("deleted", name);
    }

    @DeleteMapping("/skills/{name}/{version}")
    public Map<String, Object> deleteVersion(@PathVariable String name, @PathVariable String version,
                                             @AuthenticationPrincipal Jwt jwt) throws IOException {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "delete requires a bearer token");
        try {
            if (!publishService.deleteVersion(name, version, jwt.getSubject())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "version not found: " + name + "@" + version);
            }
        } catch (PublishException.Forbidden e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        return Map.of("deleted", name + "@" + version);
    }

    private String resolveVersion(String name, String version) throws IOException {
        if (!"latest".equals(version)) return version;
        return storage.resolveLatest(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill has no versions: " + name));
    }
}
