package com.lumen.coacervation.engine.host

/** Bound unknown future fields before the platform JSON parser recursively constructs their values. */
internal object LumenPresetInputPolicy {
    fun validate(text:String){
        require(text.length in 2..16_384)
        var depth=0;var quoted=false;var escaped=false;var started=false;var finished=false
        for(character in text){
            if(quoted){
                if(escaped)escaped=false
                else if(character=='\\')escaped=true
                else if(character=='"')quoted=false
                continue
            }
            if(depth==0){
                if(character.isWhitespace())continue
                require(!started&&!finished&&character=='{')
                started=true;depth=1;continue
            }
            when(character){
                '"'->quoted=true
                '{','['->{depth++;require(depth<=16)}
                '}',']'->{depth--;require(depth>=0);if(depth==0)finished=true}
            }
        }
        require(started&&finished&&depth==0&&!quoted)
    }
}
