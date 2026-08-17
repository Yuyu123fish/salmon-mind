@ApplicationModule(allowedDependencies = {
        "workspace :: api",
        "model :: embedding",
        "persistence :: mybatis",
        "persistence :: redis"
})
package com.yuyu.salmonmind.knowledge;

import org.springframework.modulith.ApplicationModule;
