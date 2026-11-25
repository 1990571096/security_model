package com.securitymodel.interfaces.web;

import com.securitymodel.application.note.ExperimentNoteRequest;
import com.securitymodel.application.note.ExperimentNoteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** User-private experiment notes. Every operation resolves the owner from the authenticated identity. */
@RestController
@RequestMapping("/api/experiment-notes")
public class ExperimentNoteController {

    private final ExperimentNoteService service;

    public ExperimentNoteController(ExperimentNoteService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody @Valid ExperimentNoteRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) {
        return service.listMine(limit);
    }

    @GetMapping("/{noteId}")
    public Map<String, Object> get(@PathVariable Long noteId) {
        return service.getOwned(noteId);
    }

    @PutMapping("/{noteId}")
    public Map<String, Object> update(@PathVariable Long noteId,
                                     @RequestBody @Valid UpdateRequest request) {
        return service.update(noteId, request.title(), request.nextStep());
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<Void> delete(@PathVariable Long noteId) {
        service.delete(noteId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{noteId}/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public String markdown(@PathVariable Long noteId) {
        return service.markdown(noteId);
    }

    public record UpdateRequest(@NotBlank @Size(max = 120) String title,
                                @Size(max = 500) String nextStep) {
    }
}
