@ApplicationModule(allowedDependencies = {
        "workspace :: api",
        "model :: embedding",
        "model :: rerank",
        "persistence :: mybatis",
        "persistence :: redis"
})
package com.yuyu.salmonmind.knowledge;

import org.springframework.modulith.ApplicationModule;
