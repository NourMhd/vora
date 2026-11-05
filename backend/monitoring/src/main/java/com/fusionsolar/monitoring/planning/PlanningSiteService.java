package com.fusionsolar.monitoring.planning;

import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.service.CurrentSiteAccessService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

@Service
public class PlanningSiteService {
    public static final String MANUAL = "MANUAL";
    private final MongoTemplate mongo;
    private final SiteRepository sites;
    private final CurrentSiteAccessService access;

    public PlanningSiteService(MongoTemplate mongo, SiteRepository sites, CurrentSiteAccessService access) {
        this.mongo = mongo; this.sites = sites; this.access = access;
    }

    public record SiteChoice(String brand, String siteCode, String siteName, boolean manual) {}
    private String owner() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    static String cleanName(String name) {
        if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom du site obligatoire");
        String clean = Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").trim();
        if (clean.isEmpty() || clean.length() > 120 || clean.codePoints().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nom du site obligatoire, limité à 120 caractères");
        return clean;
    }
    static String normalizedName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private List<SiteChoice> monitored() {
        return sites.findAllByOrderBySiteNameAsc().stream()
                .filter(s -> !Boolean.FALSE.equals(s.getActive()) && access.canAccess(s))
                .map(s -> new SiteChoice(s.getBrand().name(), s.getSiteCode(), s.getSiteName(), false)).toList();
    }

    public List<SiteChoice> choices() {
        List<SiteChoice> result = new ArrayList<>(monitored());
        for (PlanningSiteDocument site : mongo.find(Query.query(Criteria.where("owner").is(owner()).and("deletedAt").is(null)), PlanningSiteDocument.class))
            result.add(choice(site));
        result.sort(Comparator.comparing(s -> s.siteName() == null ? "" : s.siteName(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public SiteChoice create(String name) {
        String clean = cleanName(name), key = normalizedName(clean), currentOwner = owner();
        // Reuse an accessible monitored site if it was added while the picker was open.
        for (SiteChoice existing : monitored())
            if (existing.siteName() != null && key.equals(normalizedName(existing.siteName()))) return existing;
        String id = "manual_" + UUID.nameUUIDFromBytes((currentOwner.length() + ":" + currentOwner + ":" + key).getBytes(StandardCharsets.UTF_8));
        PlanningSiteDocument site = new PlanningSiteDocument();
        site.id = id; site.owner = currentOwner; site.name = clean; site.normalizedName = key; site.createdAt = Instant.now();
        try { return choice(mongo.insert(site)); }
        catch (DuplicateKeyException ex) {
            PlanningSiteDocument existing = requireOwned(id);
            mongo.updateFirst(Query.query(Criteria.where("id").is(id).and("owner").is(currentOwner)),
                    new org.springframework.data.mongodb.core.query.Update().unset("deletedAt"), PlanningSiteDocument.class);
            return choice(existing);
        }
    }

    /** Remove from the picker while retaining references and task history. */
    public void delete(String id) {
        requireOwned(id); // Only planning-only sites owned by this user may be removed.
        mongo.updateFirst(Query.query(Criteria.where("id").is(id).and("owner").is(owner())),
                new org.springframework.data.mongodb.core.query.Update().set("deletedAt", Instant.now()),
                PlanningSiteDocument.class);
    }

    public PlanningSiteDocument requireOwned(String id) {
        PlanningSiteDocument site = id == null ? null : mongo.findById(id, PlanningSiteDocument.class);
        if (site == null || !owner().equals(site.owner))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site manuel indisponible");
        return site;
    }

    public SiteChoice choice(PlanningSiteDocument site) { return new SiteChoice(MANUAL, site.id, site.name, true); }
}
