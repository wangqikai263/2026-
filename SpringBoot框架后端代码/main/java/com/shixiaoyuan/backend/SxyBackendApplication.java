package com.shixiaoyuan.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootApplication
@EnableScheduling
public class SxyBackendApplication {

	public static void main(String[] args) {
		// Some Windows environments deny write access to C:\WINDOWS\TEMP.
		// Force Tomcat/Spring temp files into a writable project-local directory.
		try {
			Path tmpDir = Path.of(System.getProperty("user.dir"), "data", "tmp");
			Files.createDirectories(tmpDir);
			System.setProperty("java.io.tmpdir", tmpDir.toAbsolutePath().toString());
		} catch (Exception ignored) {
			// Keep default temp dir if local override fails.
		}

		SpringApplication app = new SpringApplication(SxyBackendApplication.class);
		app.addListeners((ApplicationEnvironmentPreparedEvent event) -> {
			enforceBackendPort(event.getEnvironment());
		});
		app.run(args);
	}

	private static void enforceBackendPort(Environment environment) {
		boolean enforce = environment.getProperty("app.runtime.enforce-port", Boolean.class, true);
		if (!enforce) {
			return;
		}
		int allowedPort = environment.getProperty("app.runtime.allowed-port", Integer.class, 8080);
		int actualPort = environment.getProperty("server.port", Integer.class, 8080);
		if (actualPort != allowedPort) {
			throw new IllegalStateException(
					"仅允许后端运行在端口 " + allowedPort + "。当前检测到 server.port=" + actualPort +
					"。请仅保留 8080（后端）+ 8001（行为小模型）。");
		}
	}

}
 
