package com.fusionsolar.monitoring.planning;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/planning/sites")
public class PlanningSiteController {
    private final PlanningSiteService sites;
    public PlanningSiteController(PlanningSiteService sites) { this.sites = sites; }
    public record SiteInput(String siteName) {}
    @GetMapping public List<PlanningSiteService.SiteChoice> list() { return sites.choices(); }
    @DeleteMapping("/{id}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { sites.delete(id); }
    @PostMapping public PlanningSiteService.SiteChoice create(@RequestBody SiteInput input) {
        return sites.create(input == null ? null : input.siteName());
    }
}
