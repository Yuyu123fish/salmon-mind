@ApplicationModule(allowedDependencies = {
        "workspace :: api", "agent :: api", "persistence :: mybatis", "persistence :: redis",
        "persistence :: filesystem"
})
package com.yuyu.salmonmind.conversation;

import org.springframework.modulith.ApplicationModule;
