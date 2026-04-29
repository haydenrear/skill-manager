package dev.skillmanager.server;

import dev.skillmanager.shared.dto.Campaign;
import dev.skillmanager.shared.dto.CreateCampaignRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * CRUD endpoints for sponsored-placement campaigns.
 *
 * <p>Auth is deliberately left off for MVP — the server trusts anyone who
 * can POST. Add an auth filter before exposing this publicly.
 */
@RestController
@RequestMapping("/ads")
public class AdsController {

    private final CampaignStorage storage;

    public AdsController(CampaignStorage storage) {
        this.storage = storage;
    }

    @PostMapping("/campaigns")
    public ResponseEntity<Campaign> create(@RequestBody CreateCampaignRequest req) {
        try {
            Campaign created = storage.create(req);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/campaigns")
    public Map<String, Object> list() throws IOException {
        List<Campaign> items = storage.list();
        return Map.of("items", items, "count", items.size());
    }

    @GetMapping("/campaigns/{id}")
    public Campaign describe(@PathVariable String id) {
        return storage.get(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "campaign not found: " + id));
    }

    @DeleteMapping("/campaigns/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        if (!storage.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "campaign not found: " + id);
        }
        return Map.of("deleted", id);
    }
}
