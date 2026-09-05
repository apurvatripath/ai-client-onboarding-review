package com.apurva.onboarding.extraction;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class IntakeSchema {
    private IntakeSchema() {}
    public static final List<String> FIELDS = List.of("companyName", "contactName", "contactEmail",
            "serviceRequested", "budget", "targetLaunchDate", "projectSummary", "dependencies");
    public static final List<String> CELLS = List.of("description", "quantity", "unitPrice");
    public static final double THRESHOLD = .85;
    public static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    public static boolean missing(String value) {
        return Set.of("", "not confirmed", "not provided", "unknown", "tbd", "n/a", "not available").contains(normalize(value));
    }
    public static String canonical(String name, String value) {
        if (missing(value)) return null;
        value = value.trim().replaceAll("\\s+", " ");
        if (name.equals("contactEmail")) return value.toLowerCase(Locale.ROOT);
        if (name.equals("targetLaunchDate")) {
            for (String format : List.of("uuuu-MM-dd", "d MMMM uuuu", "d MMM uuuu", "MMMM d, uuuu")) {
                try { return LocalDate.parse(value, DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                        .withResolverStyle(java.time.format.ResolverStyle.STRICT)).toString(); }
                catch (java.time.format.DateTimeParseException ignored) {}
            }
        }
        return value;
    }
    public static boolean valid(String name, String value) {
        if(value == null) return true;
        if(name.equals("contactEmail")) return value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
        if(name.equals("targetLaunchDate")) {
            try { LocalDate.parse(value); return true; } catch (Exception e) { return false; }
        }
        if(name.equals("quantity")) return value.matches("\\d+(\\.\\d+)?") && new java.math.BigDecimal(value).signum()>0;
        return value.length()<=4000;
    }
    public static Map<String,Object> jsonSchema() {
        Map<String,Object> cell = Map.of("type","object","properties",Map.of(
                "value", Map.of("type",List.of("string","null")),
                "confidence",Map.of("type","number","minimum",0,"maximum",1),
                "evidence",Map.of("type","string")),"required",List.of("value","confidence","evidence"));
        Map<String,Object> fields=new LinkedHashMap<>(), cells=new LinkedHashMap<>();
        FIELDS.forEach(f->fields.put(f,cell)); CELLS.forEach(f->cells.put(f,cell));
        return Map.of("type","object","properties",Map.of(
                "fields",Map.of("type","object","properties",fields,"required",FIELDS),
                "lineItems",Map.of("type","array","items",Map.of("type","object","properties",cells,"required",CELLS)),
                "tablePresent",Map.of("type","boolean"),"tableComplete",Map.of("type","boolean")),
                "required",List.of("fields","lineItems","tablePresent","tableComplete"));
    }
}
