package com.hap.automaker.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.hap.automaker.config.ConfigPaths;
import com.hap.automaker.config.Jacksons;
import com.hap.automaker.model.AiAuthConfig;
import com.hap.automaker.model.OrganizationAuthConfig;
import com.hap.automaker.model.WebAuthConfig;

public final class SetupService {

    public void writeAll(
            ConfigPaths paths,
            AiAuthConfig aiAuth,
            OrganizationAuthConfig organizationAuth,
            WebAuthConfig webAuth) throws IOException {
        writeAll(paths, aiAuth, organizationAuth, webAuth, false);
    }

    public void writeAll(
            ConfigPaths paths,
            AiAuthConfig aiAuth,
            OrganizationAuthConfig organizationAuth,
            WebAuthConfig webAuth,
            boolean writeLegacyPythonFiles) throws IOException {
        Files.createDirectories(paths.credentialsDir());

        Jacksons.mapper().writeValue(paths.aiAuth().toFile(), aiAuth);
        Jacksons.mapper().writeValue(paths.organizationAuth().toFile(), organizationAuth);
        Jacksons.mapper().writeValue(paths.webAuth().toFile(), webAuth);

        if (writeLegacyPythonFiles) {
            Files.writeString(paths.authConfigPy(), buildAuthConfigPy(webAuth), StandardCharsets.UTF_8);
            Files.writeString(paths.loginCredentialsPy(), buildLoginCredentialsPy(webAuth), StandardCharsets.UTF_8);
        } else {
            Files.deleteIfExists(paths.authConfigPy());
            Files.deleteIfExists(paths.loginCredentialsPy());
        }
    }

    private String buildAuthConfigPy(WebAuthConfig webAuth) {
        return "# -*- coding: utf-8 -*-\n"
                + "ACCOUNT_ID = \"" + escape(webAuth.accountId()) + "\"\n"
                + "AUTHORIZATION = \"" + escape(webAuth.authorization()) + "\"\n"
                + "COOKIE = (\n"
                + "    \"" + escape(webAuth.cookie()) + "\"\n"
                + ")\n";
    }

    private String buildLoginCredentialsPy(WebAuthConfig webAuth) {
        return "# -*- coding: utf-8 -*-\n"
                + "LOGIN_ACCOUNT = \"" + escape(webAuth.loginAccount()) + "\"\n"
                + "LOGIN_PASSWORD = \"" + escape(webAuth.loginPassword()) + "\"\n"
                + "LOGIN_URL = \"" + escape(webAuth.loginUrl()) + "\"\n";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
