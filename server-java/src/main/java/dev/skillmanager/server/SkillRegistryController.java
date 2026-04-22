package dev.skillmanager.server;

import dev.skillmanager.registry.dto.ListResponse;
import dev.skillmanager.registry.dto.PublishResponse;
import dev.skillmanager.registry.dto.SearchResponse;
import dev.skillmanager.registry.dto.SkillSummary;
import dev.skillmanager.registry.dto.SkillVersion;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public SkillRegistryController(SkillStorage storage) {
        this.storage = storage;
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
            @RequestParam(value = "limit", defaultValue = "20") int limit
    ) throws IOException {
        List<SkillSummary> hits = storage.search(q, Math.max(1, Math.min(limit, 100)));
        return new SearchResponse(q, hits, hits.size());
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
    public ResponseEntity<Resource> download(@PathVariable String name, @PathVariable String version) throws IOException {
        String v = resolveVersion(name, version);
        Path path = storage.packagePath(name, v)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "package not found: " + name + "@" + v));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-" + v + ".tar.gz\"")
                .body(new FileSystemResource(path));
    }

    @PostMapping("/skills/{name}/{version}")
    public ResponseEntity<PublishResponse> publish(
            @PathVariable String name,
            @PathVariable String version,
            @RequestParam("package") MultipartFile pkg
    ) throws IOException {
        byte[] bytes = pkg.getBytes();
        SkillVersion record;
        try {
            record = storage.publish(name, version, bytes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        PublishResponse out = new PublishResponse(
                record.name(),
                record.version(),
                record.sha256(),
                record.sizeBytes(),
                "/skills/" + record.name() + "/" + record.version() + "/download");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @DeleteMapping("/skills/{name}")
    public Map<String, Object> deleteSkill(@PathVariable String name) throws IOException {
        if (!storage.delete(name, null)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill not found: " + name);
        }
        return Map.of("deleted", name);
    }

    @DeleteMapping("/skills/{name}/{version}")
    public Map<String, Object> deleteVersion(@PathVariable String name, @PathVariable String version) throws IOException {
        if (!storage.delete(name, version)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "version not found: " + name + "@" + version);
        }
        return Map.of("deleted", name + "@" + version);
    }

    private String resolveVersion(String name, String version) throws IOException {
        if (!"latest".equals(version)) return version;
        return storage.resolveLatest(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "skill has no versions: " + name));
    }
}
