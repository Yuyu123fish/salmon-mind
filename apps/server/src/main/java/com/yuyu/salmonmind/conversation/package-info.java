@ApplicationModule(allowedDependencies = {
        "workspace :: api", "agent :: api", "persistence :: mybatis", "persistence :: redis"
})
package com.yuyu.salmonmind.conversation;

import org.springframework.modulith.ApplicationModule;
