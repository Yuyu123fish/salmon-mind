package com.yuyu.salmonmind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 从仓库根目录的 .env 加载本地配置。已有环境变量优先，文件本身不入库。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = findProjectDotenv();
        if (envFile == null) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource("dotenvFile", parse(envFile)));
    }

    private static Path findProjectDotenv() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (dir != null) {
            if (isProjectRoot(dir)) {
                Path envFile = dir.resolve(".env");
                return Files.isRegularFile(envFile) ? envFile : null;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static boolean isProjectRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("compose.yaml"))
                || Files.isRegularFile(dir.resolve(".env.example"));
    }

    private static Map<String, Object> parse(Path envFile) {
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(envFile)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = stripQuotes(line.substring(separator + 1).trim());
                values.put(key, value);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取 .env 失败: " + envFile, ex);
        }
        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
