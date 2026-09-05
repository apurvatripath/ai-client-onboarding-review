package com.apurva.onboarding.extraction;

import com.apurva.onboarding.domain.*;
import com.apurva.onboarding.gateway.AutomationGateway;
import com.apurva.onboarding.service.AutomationUnavailableException;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;

/** Existing upload UI now uses the hardened local pipeline, never the legacy webhook. */
@Component
@Primary
public class LocalAutomationGateway implements AutomationGateway {
    private final ExtractionPipeline pipeline;
    private final ExtractionStore store;
    public LocalAutomationGateway(ExtractionPipeline pipeline,ExtractionStore store) { this.pipeline=pipeline; this.store=store; }
    @Override public ReviewOutcome review(OnboardingRequest request) {
        try {
            ExtractionResult result=pipeline.process(request.document().getBytes(),request.submissionId());
            Map<String,String> supplied=Map.of("companyName",request.companyName(),"contactName",request.contactName(),
                    "contactEmail",request.contactEmail(),"serviceRequested",request.serviceRequested(),
                    "targetLaunchDate",request.desiredStartDate().toString());
            List<String> conflicts=new ArrayList<>();
            supplied.forEach((name,value)->{
                String extracted=result.fields().get(name).value();
                if(extracted!=null && !IntakeSchema.normalize(extracted).equals(IntakeSchema.normalize(value))) conflicts.add(name);
            });
            Map<String,String> fields=new LinkedHashMap<>();
            result.fields().forEach((k,v)->fields.put(k,Objects.toString(v.value(),"")));
            ReviewStatus status=conflicts.isEmpty()?ReviewStatus.valueOf(result.reviewStatus()):ReviewStatus.MANUAL_REVIEW;
            String reason=conflicts.isEmpty()?"Document route: "+status+". Review fields: "+String.join(", ",result.reviewQueue()):"Submitted form conflicts with document: "+String.join(", ",conflicts);
            store.audit(result.submissionId(),Map.of("timestamp",java.time.Instant.now().toString(),"stage","INTAKE_COMPARISON",
                    "inputHash",result.inputHash(),"submittedFields",supplied,"conflictFields",conflicts,"reviewStatus",status.name()));
            if(!conflicts.isEmpty()) {
                Map<String,ExtractionResult.Field> flagged=new LinkedHashMap<>(result.fields());
                List<String> queue=new ArrayList<>(result.reviewQueue());
                for(String name:conflicts) {
                    var f=flagged.get(name);
                    flagged.put(name,new ExtractionResult.Field(f.value(),f.confidence(),f.confidenceSource(),"CONFLICT",f.pages(),f.evidence()));
                    if(!queue.contains(name)) queue.add(name);
                }
                store.save(new ExtractionResult(result.submissionId(),result.inputHash(),result.documentType(),result.provider(),result.pipelineVersion(),
                        result.createdAt(),status.name(),flagged,result.lineItems(),result.missingFields(),queue,result.issues(),result.pageCount(),"PENDING"));
            }
            String draft=status==ReviewStatus.COMPLETE?"Thanks for the brief. A reviewer will confirm the next steps."
                    :"Thanks for the brief. Please confirm these details before we proceed: "+String.join(", ",conflicts.isEmpty()
                    ? (result.missingFields().isEmpty()?result.reviewQueue():result.missingFields()):conflicts)+".";
            return new ReviewOutcome(result.submissionId(),status,fields,result.missingFields(),reason,draft);
        } catch(Exception e) { if(e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new AutomationUnavailableException(e); }
    }
}
