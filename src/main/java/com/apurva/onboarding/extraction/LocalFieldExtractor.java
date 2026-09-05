package com.apurva.onboarding.extraction;

import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.*;

/** Deterministic baseline for labelled English intake forms, explicitly not an AI substitute. */
public class LocalFieldExtractor implements FieldExtractor {
    private static final Map<String,String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("company name", "companyName"); LABELS.put("primary contact", "contactName");
        LABELS.put("contact name", "contactName"); LABELS.put("contact email", "contactEmail");
        LABELS.put("service requested", "serviceRequested"); LABELS.put("budget", "budget");
        LABELS.put("target launch date", "targetLaunchDate"); LABELS.put("project summary", "projectSummary");
        LABELS.put("dependencies", "dependencies");
    }
    @Override public String name() { return "local-labelled-intake-v1"; }
    @Override public String extract(String text) {
        Map<String,Object> fields = new LinkedHashMap<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        List<String> duplicateFields = new ArrayList<>();
        String current=null;
        boolean table=false, tableComplete=true;
        for (String line : text.split("\\R")) {
            line=line.trim(); if(line.isEmpty()) { current=null; continue; }
            String lower=line.toLowerCase(Locale.ROOT);
            String matched=null;
            for (String label:LABELS.keySet()) {
                Matcher m=Pattern.compile("(?i)^"+Pattern.quote(label)+"(?:\\s*[:|]\\s*|\\s+)(.*)$").matcher(line);
                if(m.matches()) {
                    current=LABELS.get(label); matched=current;
                    if(fields.containsKey(current) && !fields.get(current).equals(cell(m.group(1)))) duplicateFields.add(current);
                    fields.put(current,cell(m.group(1))); break;
                }
                if(lower.equals(label)) { current=LABELS.get(label); matched=current; fields.put(current,cell("")); break; }
            }
            if(matched!=null) continue;
            if(lower.matches("description\\s*[|].*quantity.*") || lower.matches("description\\s+quantity\\s+unit price.*")) {
                current=null; table=true; continue;
            }
            if(lower.matches("(service items|line items|deliverables|page \\d+.*|demonstration notice.*|all eight.*|fictional.*|end.*)")) { current=null; continue; }
            if (table) {
                String[] cells=line.split("\\s*\\|\\s*|\\s{2,}",-1);
                if(cells.length!=3) { tableComplete=false; continue; }
                Map<String,Object> row=new LinkedHashMap<>();
                for(int i=0;i<3;i++) row.put(IntakeSchema.CELLS.get(i),cell(cells[i]));
                rows.add(row);
            } else if(current!=null) {
                @SuppressWarnings("unchecked") var previous=(Map<String,Object>)fields.get(current);
                String before=Objects.toString(previous.get("value"),"");
                fields.put(current,cell((before+" "+line).trim()));
            }
        }
        duplicateFields.forEach(f -> { @SuppressWarnings("unchecked") var c=(Map<String,Object>)fields.get(f); c.put("confidence",.4); });
        return new ObjectMapper().writeValueAsString(Map.of("fields",fields,"lineItems",rows,
                "tablePresent",table,"tableComplete",!table || (tableComplete && !rows.isEmpty())));
    }
    private Map<String,Object> cell(String value) {
        Map<String,Object> cell=new LinkedHashMap<>();
        cell.put("value",IntakeSchema.missing(value)?null:value); cell.put("confidence",.93); cell.put("evidence",value);
        return cell;
    }
}
