@ApplicationModule(allowedDependencies = {
        "model :: chat", "persistence :: redis", "knowledge :: retrieval", "websearch :: api"
})
package com.yuyu.salmonmind.agent;

import org.springframework.modulith.ApplicationModule;
