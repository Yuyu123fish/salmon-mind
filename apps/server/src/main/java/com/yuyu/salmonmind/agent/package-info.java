@ApplicationModule(allowedDependencies = {"model :: chat", "persistence :: redis", "knowledge :: retrieval"})
package com.yuyu.salmonmind.agent;

import org.springframework.modulith.ApplicationModule;
