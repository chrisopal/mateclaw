package vip.mate.semantic.extraction;

import java.util.*;
import vip.mate.semantic.core.fact.*;
import vip.mate.semantic.core.identity.SemanticIds.EntityId;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;

final class ExtractionMapping {
    static RawSuggestion raw(ExtractionDtos.EditRequest e){
        if(!Set.of("PROPERTY","RELATION").contains(e.predicateKind())||!Set.of("UNKNOWN","INTERVAL").contains(e.validityKind())||(e.status()!=null&&!Set.of("OPEN","IGNORED").contains(e.status())))throw new IllegalArgumentException("Invalid suggestion enum");
        if("UNKNOWN".equals(e.validityKind())&&(e.validFrom()!=null||e.validTo()!=null))throw new IllegalArgumentException("Unknown time cannot have endpoints");
        if(!"DECIMAL".equals(e.valueType())&&e.unit()!=null)throw new IllegalArgumentException("Unit only belongs to numeric values");
        StatementValue value="ENTITY".equals(e.valueType())?null:value(e.valueType(),e.value(),e.unit());
        return new RawSuggestion(new ObjectMention("subject",e.subjectTypeKey(),e.subjectName()),
            "PROPERTY".equals(e.predicateKind())?PredicateRef.property(e.predicateKey()):PredicateRef.relation(e.predicateKey()),value,
            "ENTITY".equals(e.valueType())?new ObjectMention("target",e.targetTypeKey(),e.targetName()):null,
            "UNKNOWN".equals(e.validityKind())?Validity.unknown():Validity.interval(e.validFrom(),e.validTo()),
            e.quotes()==null?List.of():e.quotes().stream().map(q->new Quote(q.startCodePoint(),q.endCodePoint(),q.exactQuote())).toList());
    }
    static StatementValue value(String type,String value,String unit){return switch(type){
        case "TEXT"->new StatementValue.TextValue(value);case "DECIMAL"->new StatementValue.DecimalValue(value,unit);
        case "BOOLEAN"->{if(!"true".equals(value)&&!"false".equals(value))throw new IllegalArgumentException("Boolean required");yield new StatementValue.BooleanValue(Boolean.parseBoolean(value));}
        case "DATE"->new StatementValue.DateValue(value);case "INSTANT"->new StatementValue.InstantValue(value);
        default->throw new IllegalArgumentException("Unsupported value type");};}
    static String type(StatementValue v){return switch(v){case StatementValue.TextValue x->"TEXT";case StatementValue.DecimalValue x->"DECIMAL";case StatementValue.BooleanValue x->"BOOLEAN";case StatementValue.DateValue x->"DATE";case StatementValue.InstantValue x->"INSTANT";case StatementValue.EntityValue x->"ENTITY";case null->"ENTITY";};}
    static String value(StatementValue v){return switch(v){case StatementValue.TextValue x->x.value();case StatementValue.DecimalValue x->x.value().toPlainString();case StatementValue.BooleanValue x->Boolean.toString(x.value());case StatementValue.DateValue x->x.value().toString();case StatementValue.InstantValue x->x.value().toString();case StatementValue.EntityValue x->null;case null->null;};}
    static String unit(StatementValue v){return v instanceof StatementValue.DecimalValue d?d.unit():null;}
    static ExtractionDtos.SuggestionView view(Suggestion s,Receipt receipt){return view(s,receipt,null);}
    static ExtractionDtos.SuggestionView view(Suggestion s,Receipt receipt,String pending){RawSuggestion r=s.content();StatementContent c=s.mappedContent();
        return new ExtractionDtos.SuggestionView(s.suggestionId(),s.editVersion(),s.status().name(),r.subject().typeKey(),r.subject().name(),c==null?null:c.subjectId().value(),r.predicate() instanceof PredicateRef.PropertyRef?"PROPERTY":"RELATION",r.predicate().key(),type(r.value()),value(r.value()),unit(r.value()),r.target()==null?null:r.target().typeKey(),r.target()==null?null:r.target().name(),c!=null&&c.value() instanceof StatementValue.EntityValue e?e.entityId().value():null,r.validity().kind().name(),r.validity().fromInclusive(),r.validity().toExclusive(),r.quotes().stream().map(q->new ExtractionDtos.Quote(q.startCodePoint(),q.endCodePoint(),q.exactQuote())).toList(),s.diagnostics().stream().map(v->v.code()).toList(),receipt==null?null:receipt.submission().statementId(),pending);
    }
}
