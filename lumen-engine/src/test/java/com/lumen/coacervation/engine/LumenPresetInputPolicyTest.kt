package com.lumen.coacervation.engine

import com.lumen.coacervation.engine.host.LumenPresetInputPolicy
import org.junit.Assert.assertThrows
import org.junit.Test

class LumenPresetInputPolicyTest {
    @Test fun validUnknownFieldsAndQuotedBracketsAreAllowed(){
        LumenPresetInputPolicy.validate(""" {"schema":1,"future":{"text":"escaped \" ] { ","list":[{},1]}} """)
    }
    @Test fun recursiveUnknownFieldsAreBoundedBeforeParsing(){
        assertThrows(IllegalArgumentException::class.java){LumenPresetInputPolicy.validate("{\"schema\":1,\"future\":"+"[".repeat(16)+"0"+"]".repeat(16)+"}")}
    }
    @Test fun oversizeIncompleteAndMultipleRootDocumentsAreRejected(){
        for(text in listOf(" ".repeat(16_385),"{\"value\":\"unterminated}","{\"schema\":1}{}","{\"schema\":1} trailing")){
            assertThrows(IllegalArgumentException::class.java){LumenPresetInputPolicy.validate(text)}
        }
    }
}
