package com.preclinic.backend.common;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built Angular app from a folder (installed/desktop use). Any address that is not a real
 * file and not an API call gets {@code index.html}, so reloading or bookmarking an in-app page
 * such as /patient/patients-list works. Off unless {@code clinstra.web.dir} is set.
 */
@Configuration
@ConditionalOnExpression("!'${clinstra.web.dir:}'.isEmpty()")
class WebAppConfig implements WebMvcConfigurer {

	private final Path dir;

	WebAppConfig(@Value("${clinstra.web.dir}") String webDir) {
		this.dir = Path.of(webDir).toAbsolutePath().normalize();
	}

	// Spring never hands the bare "/" to a resource resolver, so the home page is forwarded explicitly.
	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/").setViewName("forward:/index.html");
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**")
				.addResourceLocations(dir.toUri().toString())
				.resourceChain(true)
				.addResolver(new PathResourceResolver() {
					@Override
					protected Resource getResource(String resourcePath, Resource location) throws IOException {
						Path wanted = dir.resolve(resourcePath).normalize();
						if (!wanted.startsWith(dir)) {
							return null; // never serve anything outside the web folder
						}
						Resource file = location.createRelative(resourcePath);
						if (!resourcePath.isEmpty() && file.exists() && file.isReadable() && !java.nio.file.Files.isDirectory(wanted)) {
							return file;
						}
						if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
							return null;
						}
						return new FileSystemResource(dir.resolve("index.html"));
					}
				});
	}
}
