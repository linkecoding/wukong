package com.wukong.phone.script;
import com.wukong.phone.script.IJsBridge;
import com.wukong.phone.script.IJsResult;
oneway interface IJsRuntime {
    void execute(String code, long timeoutMs, IJsBridge bridge, IJsResult result);
    void cancel();
}
