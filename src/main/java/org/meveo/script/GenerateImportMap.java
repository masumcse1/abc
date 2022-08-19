package org.meveo.script;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.meveo.admin.exception.BusinessException;
import org.meveo.commons.utils.MeveoFileUtils;
import org.meveo.model.persistence.JacksonUtil;
import org.meveo.service.script.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenerateImportMap extends Script {

    private static Logger LOGGER = LoggerFactory.getLogger(GenerateImportMap.class);

    public static String generateImportMap(File directory) throws BusinessException {
        try {
            Map<String, String> importMap = new HashMap<>();
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("imports", importMap);

            Map<String, Object> packageLock = JacksonUtil.read(new File(directory, "package-lock.json"), Map.class);

            Map<String, Object> packages = (Map<String, Object>) packageLock.get("packages");
            packages.entrySet()
                .stream()
                .filter(e -> !e.getKey().isEmpty())
                .forEach(e -> {
                    try {
                        File packageJsonFile = new File(directory, e.getKey() + File.separator + "package.json");
                        Map<String, Object> packageJson = (Map<String, Object>) JacksonUtil.read(packageJsonFile, Map.class);
                        String name = (String) packageJson.get("name");
                        String mainFile = (String) packageJson.get("main");
                        if (StringUtils.isNotBlank(mainFile)) {
                            importMap.put(name, "./" + e.getKey() + "/" + mainFile);
                        }
                        importMap.put(name + "/" , "./" + e.getKey() + "/");
                    } catch (Exception e2) {
                        LOGGER.error("Failed to parse dependency", e);
                    }
                });

            String importMapString = JacksonUtil.toStringPrettyPrinted(resultMap);
            String importMapJs = "window.importmap = " + importMapString + ";";
            MeveoFileUtils.writeAndPreserveCharset(importMapJs, new File(directory, "importmap.js"));
            return importMapString;
        } catch (Exception e) {
            throw new BusinessException(e);
        }
    }
}
