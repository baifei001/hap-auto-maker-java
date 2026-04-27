package com.hap.automaker.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hap.automaker.ai.AiTextClient;
import com.hap.automaker.ai.AiJsonParser;
import com.hap.automaker.ai.HttpAiTextClient;
import com.hap.automaker.config.RepoPaths;
import com.hap.automaker.service.SpecGeneratorService;
import com.hap.automaker.service.SpecNormalizer;
import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "make-app", mixinStandardHelpOptions = true, description = "Generate a spec and optionally execute it")
public class MakeAppCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MakeAppCommand.class);

    private AiTextClient aiTextClientOverride;
    private ExecuteRequirementsCommand executeRequirementsCommandOverride;

    @Option(names = "--requirements", defaultValue = "")
    private String requirements;

    @Option(names = "--spec-json", defaultValue = "")
    private String specJson;

    @Option(names = "--output", defaultValue = "")
    private String output;

    @Option(names = "--language", defaultValue = "auto")
    private String language;

    @Option(names = "--no-execute", defaultValue = "false")
    private boolean noExecute;

    @Option(names = "--repo-root", defaultValue = "")
    private String repoRoot;

    @Override
    public Integer call() throws Exception {
        RepoPaths detected = repoRoot.isBlank()
                ? RepoPaths.detect(Path.of("").toAbsolutePath())
                : new RepoPaths(Path.of(repoRoot).toAbsolutePath().normalize(),
                        Path.of(repoRoot).toAbsolutePath().normalize().resolve("hap-auto-maker-java"));

        Path savedSpec;
        if (!specJson.isBlank()) {
            savedSpec = Path.of(specJson).toAbsolutePath().normalize();
        } else {
            if (requirements.isBlank()) {
                throw new IllegalArgumentException("--requirements or --spec-json is required");
            }
            String generationLanguage = "auto".equalsIgnoreCase(language) ? "zh" : language;
            SpecGeneratorService service = new SpecGeneratorService(
                    resolveAiTextClient(),
                    new AiJsonParser(),
                    new SpecNormalizer());
            ObjectNode spec = service.generateSpec(detected.repoRoot(), requirements, generationLanguage);
            savedSpec = service.saveSpec(
                    detected.repoRoot(),
                    spec,
                    output.isBlank() ? null : Path.of(output).toAbsolutePath().normalize());
        }

        logger.info("Spec file: {}", savedSpec);
        if (noExecute) {
            return 0;
        }
        return resolveExecuteRequirementsCommand().executeWithSpec(detected.repoRoot(), savedSpec, language, false);
    }

    void setAiTextClientOverride(AiTextClient aiTextClientOverride) {
        this.aiTextClientOverride = aiTextClientOverride;
    }

    void setExecuteRequirementsCommandOverride(ExecuteRequirementsCommand executeRequirementsCommandOverride) {
        this.executeRequirementsCommandOverride = executeRequirementsCommandOverride;
    }

    private AiTextClient resolveAiTextClient() {
        return aiTextClientOverride != null ? aiTextClientOverride : new HttpAiTextClient();
    }

    private ExecuteRequirementsCommand resolveExecuteRequirementsCommand() {
        return executeRequirementsCommandOverride != null
                ? executeRequirementsCommandOverride
                : new ExecuteRequirementsCommand();
    }
}
