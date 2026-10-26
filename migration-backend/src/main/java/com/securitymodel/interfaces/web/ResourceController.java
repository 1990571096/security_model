package com.securitymodel.interfaces.web;

import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.resource.ResourceCommandService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.hyper.Hyper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import jakarta.validation.Valid;

/**
 * Native Spring Boot 3 resource endpoints.
 *
 * These paths intentionally preserve the old frontend contract, so the UI can
 * switch from the compatibility proxy without changing its business code.
 */
@RestController
public class ResourceController {

    private final ResourceQueryService resourceQueryService;
    private final ResourceCommandService resourceCommandService;

    public ResourceController(ResourceQueryService resourceQueryService, ResourceCommandService resourceCommandService) {
        this.resourceQueryService = resourceQueryService;
        this.resourceCommandService = resourceCommandService;
    }

    @GetMapping("/api/dataset")
    public List<Dataset> datasets() { return resourceQueryService.findDatasets(); }

    @PostMapping("/api/dataset")
    public ResponseEntity<Dataset> createDataset(@Valid @RequestBody ResourceCommandService.DatasetInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(resourceCommandService.saveDataset(null, input));
    }

    @PutMapping("/api/dataset/{datasetId}")
    public Dataset updateDataset(@PathVariable Long datasetId,
                                 @Valid @RequestBody ResourceCommandService.DatasetInput input) {
        return resourceCommandService.saveDataset(datasetId, input);
    }

    @DeleteMapping("/api/dataset/{datasetId}")
    public Dataset disableDataset(@PathVariable Long datasetId) {
        return resourceCommandService.disableDataset(datasetId);
    }

    @GetMapping("/api/algorithm")
    public List<Algorithm> algorithms() { return resourceQueryService.findAlgorithms(); }

    @PostMapping("/api/algorithm")
    public ResponseEntity<Algorithm> createAlgorithm(@Valid @RequestBody ResourceCommandService.AlgorithmInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(resourceCommandService.saveAlgorithm(null, input));
    }

    @PutMapping("/api/algorithm/{algorithmId}")
    public Algorithm updateAlgorithm(@PathVariable Long algorithmId,
                                     @Valid @RequestBody ResourceCommandService.AlgorithmInput input) {
        return resourceCommandService.saveAlgorithm(algorithmId, input);
    }

    @DeleteMapping("/api/algorithm/{algorithmId}")
    public Algorithm disableAlgorithm(@PathVariable Long algorithmId) {
        return resourceCommandService.disableAlgorithm(algorithmId);
    }

    @GetMapping("/api/dataset/available")
    public List<Dataset> availableDatasets(
            @RequestParam(required = false) String datasetType) {
        return resourceQueryService.findAvailableDatasets(datasetType);
    }

    @GetMapping("/api/dataset/{datasetId}")
    public ResponseEntity<Dataset> dataset(@PathVariable Long datasetId) {
        return entityOrNotFound(resourceQueryService.findDataset(datasetId));
    }

    @GetMapping("/api/algorithm/available")
    public List<Algorithm> availableAlgorithms(
            @RequestParam(required = false) String defenseStage,
            @RequestParam(required = false) String algorithmType) {
        return resourceQueryService.findAvailableAlgorithms(defenseStage, algorithmType);
    }

    @GetMapping("/api/algorithm/{algorithmId}")
    public ResponseEntity<Algorithm> algorithm(@PathVariable Long algorithmId) {
        return entityOrNotFound(resourceQueryService.findAlgorithm(algorithmId));
    }

    @GetMapping("/api/hyper/available")
    public List<Hyper> availableHyper() {
        return resourceQueryService.findAvailableHyper();
    }

    @GetMapping("/api/hyper/{templateId}")
    public ResponseEntity<Hyper> hyper(@PathVariable Long templateId) {
        return entityOrNotFound(resourceQueryService.findHyper(templateId));
    }

    @GetMapping("/api/hyper/algorithm/{algorithmId}")
    public List<Hyper> hyperByAlgorithm(@PathVariable Long algorithmId) {
        return resourceQueryService.findHyperByAlgorithm(algorithmId);
    }

    @GetMapping("/api/hyper/algorithm/{algorithmId}/default")
    public ResponseEntity<Hyper> defaultHyper(@PathVariable Long algorithmId) {
        return entityOrNotFound(resourceQueryService.findDefaultHyper(algorithmId));
    }

    private <T> ResponseEntity<T> entityOrNotFound(T entity) {
        return entity == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).build()
                : ResponseEntity.ok(entity);
    }
}
