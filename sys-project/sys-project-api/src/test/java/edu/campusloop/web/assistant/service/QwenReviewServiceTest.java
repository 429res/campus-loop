package edu.campusloop.web.assistant.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QwenReviewServiceTest {
 final QwenReviewService ai=new QwenReviewService(new ObjectMapper(),"","https://example.invalid","test-model",System.getProperty("java.io.tmpdir"));
 @Test void malformedOrIncompleteVerdictsNeverAuthorizeChanges(){for(String value:List.of("{}","{\"decision\":\"APPROVE\",\"confidence\":\"0.99\",\"reason\":\"合规\",\"checks\":[]}","{\"decision\":\"OTHER\",\"confidence\":1,\"reason\":\"合规\",\"checks\":[]}","{\"decision\":\"APPROVE\",\"confidence\":1,\"reason\":\"\",\"checks\":[]}"))assertThrows(Exception.class,()->ai.parse(value));}
 @Test void confidenceBoundsAndUncertaintyAreEnforced()throws Exception{assertTrue(ai.parse("{\"decision\":\"APPROVE\",\"confidence\":0.99,\"reason\":\"普通教材\",\"checks\":[]}").certain());for(double score:List.of(-1.,0.89,1.01,Double.NaN))assertFalse(new QwenReviewService.Result("APPROVE",score,"合规","test",List.of()).certain());assertFalse(ai.parse("{\"decision\":\"REVIEW\",\"confidence\":1,\"reason\":\"证据不足\",\"checks\":[]}").certain());}
}
