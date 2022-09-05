package org.meveo.script;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.core.type.TypeReference;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.meveo.admin.exception.BusinessException;
import org.meveo.api.persistence.CrossStorageApi;
import org.meveo.commons.utils.ParamBean;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.model.crm.CustomFieldTemplate;
import org.meveo.model.crm.custom.CustomFieldTypeEnum;
import org.meveo.model.crm.custom.EntityCustomAction;
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.WebApplication;
import org.meveo.model.git.GitRepository;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
import org.meveo.model.persistence.JacksonUtil;
import org.meveo.model.storage.Repository;
import org.meveo.persistence.CrossStorageService;
import org.meveo.security.MeveoUser;
import org.meveo.service.admin.impl.MeveoModuleService;
import org.meveo.service.crm.impl.CustomFieldInstanceService;
import org.meveo.service.crm.impl.CustomFieldTemplateService;
import org.meveo.service.custom.CustomEntityTemplateService;
import org.meveo.service.custom.EntityCustomActionService;
import org.meveo.service.git.GitClient;
import org.meveo.service.git.GitHelper;
import org.meveo.service.git.GitRepositoryService;
import org.meveo.service.script.Script;
import org.meveo.service.storage.RepositoryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GenerateWebAppScript extends Script {
	private static final String MASTER_BRANCH = "master";
	private static final String MEVEO_BRANCH = "meveo";
	private static final String MV_TEMPLATE_REPO = "https://github.com/meveo-org/mv-template.git";
	private static final String LOG_SEPARATOR =
			"***********************************************************";
	private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();
	private static final String WEB_APP_TEMPLATE = WebApplication.class.getSimpleName();
	private static final String PARENT = "Parent";
	private static final String PAGE_TEMPLATE = "Parent.js";
	private static final String INDEX_TEMPLATE = "index.js";
	private static final String LOCALHOST = "http://localhost:8080/";
	private static final String KEYCLOAK_URL = "http://host.docker.internal:8081/auth";
	private static final String KEYCLOAK_REALM = "meveo";
	private static final String KEYCLOAK_RESOURCE = "meveo-web";
	private static final String MODULE_CODE = "MODULE_CODE";
	private static final String AFFIX = "-UI";
	private static final Logger LOG = LoggerFactory.getLogger(GenerateWebAppScript.class);
	private String CRLF = WebAppScriptHelper.CRLF;
	private String SLASH = File.separator;
	private String baseUrl = null;
	private CrossStorageService crossStorageService = getCDIBean(CrossStorageService.class);
	private CustomEntityTemplateService cetService = getCDIBean(CustomEntityTemplateService.class);
	private CustomFieldInstanceService cfiService = getCDIBean(CustomFieldInstanceService.class);
	private CustomFieldTemplateService cftService = getCDIBean(CustomFieldTemplateService.class);
	private EntityCustomActionService ecaService = getCDIBean(EntityCustomActionService.class);
	private GitClient gitClient = getCDIBean(GitClient.class);
	private GitRepositoryService gitRepositoryService = getCDIBean(GitRepositoryService.class);
	private MeveoModuleService meveoModuleService = getCDIBean(MeveoModuleService.class);
	private RepositoryService repositoryService = getCDIBean(RepositoryService.class);
	private ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
	private CrossStorageApi crossStorageApi = getCDIBean(CrossStorageApi.class);

	private Repository repository;
	private String moduleCode;

	public String getModuleCode() {
		return this.moduleCode;
	}

	public void setModuleCode(String moduleCode) {
		this.moduleCode = moduleCode;
	}

	public Repository getDefaultRepository() {
		if (repository == null) {
			repository = repositoryService.findDefaultRepository();
		}
		return repository;
	}

	@Override
	public void execute(Map<String, Object> parameters) throws BusinessException {
		LOG.debug("START - GenerateWebAppScript.execute()");
		super.execute(parameters);
		LOG.debug("moduleCode: {}", moduleCode);
		if (moduleCode == null) {
			throw new BusinessException("moduleCode not set");
		}
		MeveoModule module = meveoModuleService.findByCode(moduleCode);

		MeveoUser user = (MeveoUser) parameters.get(CONTEXT_CURRENT_USER);

		ParamBean appConfig = paramBeanFactory.getInstance();
		String remoteUrl = appConfig.getProperty("meveo.git.directory.remote.url", null);
		String remoteUsername = appConfig.getProperty("meveo.git.directory.remote.username", null);
		String remotePassword = appConfig.getProperty("meveo.git.directory.remote.password", null);
		String appContext = appConfig.getProperty("meveo.admin.webContext", "");
		String serverUrl = appConfig.getProperty("meveo.admin.baseUrl", null);
		String keycloakUrl = System.getProperty("meveo.keycloak.url");
		String keycloakRealm = System.getProperty("meveo.keycloak.realm");
		String keycloakResource = System.getProperty("meveo.keycloak.client");

		this.baseUrl = serverUrl;
		if (this.baseUrl == null) {
			this.baseUrl = LOCALHOST;
		}

		this.baseUrl = this.baseUrl.strip().endsWith("/") ? this.baseUrl : this.baseUrl + "/";
		this.baseUrl = this.baseUrl + appContext;

		LOG.debug("user: {}", user);
		LOG.debug("baseUrl: {}", baseUrl);

		if (module != null) {
			LOG.debug("Module found: {}", module.getCode());
			Set<MeveoModuleItem> moduleItems = module.getModuleItems();
			LOG.debug("CUSTOM_TEMPLATE={}", CUSTOM_TEMPLATE);
			List<String> entityCodes =
					moduleItems.stream().filter(item -> CUSTOM_TEMPLATE.equals(item.getItemClass()))
							.map(entity -> entity.getItemCode()).collect(Collectors.toList());
			LOG.debug("entityCodes: {}", entityCodes);

			WebApplication webapp = crossStorageApi.find(getDefaultRepository(), WebApplication.class)
					.by("code", module.getCode()).getResult();

			// SAVE WEB APPLICATION CEI
			CustomEntityInstance webApplicationCEI = new CustomEntityInstance();
			webApplicationCEI.setCode(moduleCode);
			webApplicationCEI.setCetCode(WEB_APP_TEMPLATE);
			webApplicationCEI.setDescription(moduleCode + " Web Application");
			if (webapp != null) {
				LOG.debug("UPDATE Web App CEI");
				String uuid = webapp.getUuid();
				LOG.debug("uuid: {}", uuid);
				webApplicationCEI.setUuid(uuid);
			}
			try {
				cfiService.setCFValue(webApplicationCEI, "code", moduleCode);
				cfiService.setCFValue(webApplicationCEI, "ROOT_PATH", "/git/" + moduleCode + AFFIX);
				cfiService.setCFValue(webApplicationCEI, "BASE_URL", "/meveo/rest/webapp/" + moduleCode);
				cfiService.setCFValue(webApplicationCEI, "entities", entityCodes);
				cfiService.setCFValue(webApplicationCEI, "label",
						WebAppScriptHelper.toTitleName(moduleCode));
				crossStorageService.createOrUpdate(getDefaultRepository(), webApplicationCEI);
			} catch (Exception e) {
				LOG.error("Failed creating cei {}", e);
				throw new BusinessException("Failed creating cei " + e.getMessage());
			}

			// SAVE COPY OF MV-TEMPLATE TO MEVEO GIT REPOSITORY
			GitRepository webappTemplateRepo = gitRepositoryService.findByCode(WEB_APP_TEMPLATE);

			if (webappTemplateRepo == null) {
				LOG.debug("CREATE NEW GitRepository: {}", WEB_APP_TEMPLATE);
				webappTemplateRepo = new GitRepository();
				webappTemplateRepo.setCode(WEB_APP_TEMPLATE);
				webappTemplateRepo.setDescription(WEB_APP_TEMPLATE + " Template repository");
				webappTemplateRepo.setRemoteOrigin(MV_TEMPLATE_REPO);
				webappTemplateRepo.setDefaultRemoteUsername("");
				webappTemplateRepo.setDefaultRemotePassword("");
				gitRepositoryService.create(webappTemplateRepo);
			} else {
				gitClient.pull(webappTemplateRepo, "", "");
			}

			File webappTemplateDirectory = GitHelper.getRepositoryDir(user, WEB_APP_TEMPLATE);
			Path webappTemplatePath = webappTemplateDirectory.toPath();
			LOG.debug("webappTemplate path: {}", webappTemplatePath.toString());

			// COPY TEMPLATE INTO A SEPARATE MODULE DIRECTORY
			GitRepository moduleWebAppRepo = gitRepositoryService.findByCode(moduleCode + AFFIX);

			if (moduleWebAppRepo == null) {
				moduleWebAppRepo = new GitRepository();
				moduleWebAppRepo.setCode(moduleCode + AFFIX);
				moduleWebAppRepo
						.setDescription(WebAppScriptHelper.toTitleName(moduleCode) + " Template repository");
				moduleWebAppRepo.setRemoteOrigin(remoteUrl);
				moduleWebAppRepo.setDefaultRemoteUsername(remoteUsername);
				moduleWebAppRepo.setDefaultRemotePassword(remotePassword);
				gitRepositoryService.create(moduleWebAppRepo);
			}

			gitClient.checkout(moduleWebAppRepo, MEVEO_BRANCH, true);
			String moduleWebAppBranch = gitClient.currentBranch(moduleWebAppRepo);
			LOG.debug("moduleWebApp branch: {}", moduleWebAppBranch);

			File moduleWebAppDirectory = GitHelper.getRepositoryDir(user, moduleCode + AFFIX);
			Path moduleWebAppPath = moduleWebAppDirectory.toPath();

			LOG.debug("moduleWebApp path: {}", moduleWebAppPath.toString());
			
			/////////////////////////////////////////////////////////
			
		//	19:33:51,302 INFO  [stdout] (default task-47) outputFile./meveodata/default/git/mymodule-UI/model/index.js

	//		19:33:51,302 INFO  [stdout] (default task-47) inputfile./meveodata/default/git/WebApplication/model/index.js

		//	19:33:51,303 INFO  [stdout] (default task-47) outputFile./meveodata/default/git/mymodule-UI/utils/index.js

			//19:33:51,303 INFO  [stdout] (default task-47) inputfile./meveodata/default/git/WebApplication/utils/index.js

	//		19:33:51,317 INFO  [stdout] (default task-47) -------------------------END------------------------------------------

			try (Stream<Path> sourceStream = Files.walk(webappTemplatePath)) {
				List<Path> sources = sourceStream.collect(Collectors.toList());
				List<Path> destinations =
						sources.stream().map(webappTemplatePath::relativize).map(moduleWebAppPath::resolve)
								.collect(Collectors.toList());

				List<File> filesToCommit = new ArrayList<>();

				for (int index = 0; index < sources.size(); index++) {
					Path sourcePath = sources.get(index);
					Path destinationPath = destinations.get(index);
					
					 if (sourcePath.toString().contains(INDEX_TEMPLATE)) {
					try {
						File outputFile = new File(destinationPath.toString());
						System.out.println("outputFile"+outputFile);
						File inputfile = new File(sourcePath.toString());
						System.out.println("inputfile"+inputfile);
						String inputcontent = FileUtils.readFileToString(inputfile,StandardCharsets.UTF_8.name());
					    FileUtils.write(outputFile, inputcontent, StandardCharsets.UTF_8);
					    filesToCommit.add(outputFile);
					} catch (Exception e) {
						throw new BusinessException("Failed creating file." + e.getMessage());
					}
					 }
				 
				}

				if (!filesToCommit.isEmpty()) {
					gitClient.commitFiles(moduleWebAppRepo, filesToCommit, "Initialize Entity GUI template");
					
				}

				System.out.println("-------------------------END------------------------------------------");
		

			} catch (IOException ioe) {
				throw new BusinessException(ioe);
			}
		}
		LOG.debug("END - GenerateWebAppScript.execute()");
	}

	private File searchAndReplace(File sourceFile, File destinationFile, String stringToReplace,
			String replacement) throws BusinessException {
		StringWriter writer = new StringWriter();
		LOG.debug("sourceFile: {}", sourceFile);
		LOG.debug("destinationFile: {}", destinationFile);
		LOG.debug("stringToReplace: {}", stringToReplace);
		LOG.debug("replacement: {}", replacement);
		try {
			IOUtils.copy(new InputStreamReader(new FileInputStream(sourceFile)), writer);
			String fileContent = writer.toString();
			String outputContent = fileContent.replace(stringToReplace, replacement);
			FileUtils.write(destinationFile, outputContent, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BusinessException("Failed while trying to replace string: " + e.getMessage());
		}
		return destinationFile;
	}

	private File searchAndReplace(File sourceFile, File destinationFile,
			Map<String, String> substitutions) throws BusinessException {
		StringWriter writer = new StringWriter();
		LOG.debug("sourceFile: {}", sourceFile);
		LOG.debug("destinationFile: {}", destinationFile);
		LOG.debug("substitutions: {}", substitutions);
		try {
			IOUtils.copy(new InputStreamReader(new FileInputStream(sourceFile)), writer);
			String outputContent = writer.toString();
			for (Entry<String, String> entry : substitutions.entrySet()) {
				String stringToReplace = entry.getKey();
				String replacement = entry.getValue();
				outputContent = outputContent.replace(stringToReplace, replacement);
			}
			FileUtils.write(destinationFile, outputContent, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new BusinessException("Failed while trying to replace string: " + e.getMessage());
		}
		return destinationFile;
	}

	private List<File> generatePages(FileTransformer transformer) throws BusinessException {
		List<File> files = new ArrayList<>();
		List<Substitute> substitutes = new ArrayList<>();

		String source = transformer.getSource().toString();
		File sourceFile = transformer.getSource().toFile();

		StringWriter writer = new StringWriter();
		try {
			IOUtils.copy(new InputStreamReader(new FileInputStream(sourceFile)), writer);
		} catch (IOException e) {
			LOG.error("Failed loading template:{}, with error: {}", sourceFile, e.getMessage());
			return files;
		}

		if (source.contains("ListPage.js")) {
			LOG.debug("GENERATE LIST PAGE");
			substitutes
					.add(new Substitute("ParentEntityListPage", "%sListPage", WebAppScriptHelper.PASCAL));
			substitutes
					.add(new Substitute("parent-entity-list-page", "%s-list-page", WebAppScriptHelper.TAG));
			substitutes.add(new Substitute(PARENT, "%s", WebAppScriptHelper.PASCAL));
			substitutes.add(new Substitute("GET", "POST", WebAppScriptHelper.CONSTANT));
			files = transformer.generateFiles(substitutes);
		}
		if (source.contains("NewPage.js")) {
			LOG.debug("GENERATE NEW PAGE");
			substitutes
					.add(new Substitute("ParentEntityNewPage", "%sNewPage", WebAppScriptHelper.PASCAL));
			substitutes
					.add(new Substitute("parent-entity-new-page", "%s-new-page", WebAppScriptHelper.TAG));
			substitutes.add(new Substitute(PARENT, "%s", WebAppScriptHelper.PASCAL));
			files = transformer.generateFiles(substitutes);

		}
		if (source.contains("UpdatePage.js")) {
			LOG.debug("GENERATE UPDATE PAGE");
			substitutes
					.add(new Substitute("ParentEntityUpdatePage", "%sUpdatePage", WebAppScriptHelper.PASCAL));
			substitutes.add(
					new Substitute("parent-entity-update-page", "%s-update-page", WebAppScriptHelper.TAG));
			substitutes.add(new Substitute(PARENT, "%s", WebAppScriptHelper.PASCAL));
			files = transformer.generateFiles(substitutes);
		}
		return files;
	}

	private Set<String> iterateRefSchemas(String entityCode, Set<String> allSchemas) {
      	if (allSchemas.contains(entityCode)) {
          return allSchemas;
        }
		Set<String> refSchemaCodes = allSchemas;
		refSchemaCodes.add(entityCode);
		CustomEntityTemplate entityTemplate = cetService.findByCodeOrDbTablename(entityCode);
		Map<String, CustomFieldTemplate> fields =
				cftService.findByAppliesTo(entityTemplate.getAppliesTo());
		for (Entry<String, CustomFieldTemplate> entry : fields.entrySet()) {
			String key = entry.getKey();
			CustomFieldTemplate field = entry.getValue();
			String fieldEntityCode = field.getEntityClazzCetCode();
			boolean isEntity = fieldEntityCode != null;
			boolean isAdded = refSchemaCodes.contains(key);
			boolean isCet = isEntity && !fieldEntityCode.contains(".");
			if (!isAdded && isCet) {
				LOG.debug("Adding to all schemas: {}", refSchemaCodes);
				refSchemaCodes.addAll(iterateRefSchemas(fieldEntityCode, refSchemaCodes));
			}
		}
		LOG.debug("Added Schemas: {}", refSchemaCodes);
		return refSchemaCodes;
	}

	private List<File> generateModels(FileTransformer transformer) throws BusinessException {
		List<File> files = new ArrayList<>();
		LOG.debug("GENERATE MODELS");
		LOG.debug("source path: {}", transformer.getSource());
		String source = transformer.getSource().toString();

		 if (source.contains(INDEX_TEMPLATE)) {
			String destination = transformer.getDestination().toString();
			try {
				File outputFile = new File(destination.toString());
				File inputfile = new File(source.toString());
				String inputcontent = FileUtils.readFileToString(inputfile,StandardCharsets.UTF_8.name());
			    FileUtils.write(outputFile, inputcontent, StandardCharsets.UTF_8);
				files.add(outputFile);
			} catch (Exception e) {
				throw new BusinessException("Failed creating file." + e.getMessage());
			}
		}

		return files;
	}
}


class Substitute {
	private String regex = null;
	private String pattern = null;
	private UnaryOperator<String> format = null;

	public Substitute() {
		super();
	}

	public Substitute(String regex, String pattern, UnaryOperator<String> format) {
		this.regex = regex;
		this.pattern = pattern;
		this.format = format;
	}

	public String getRegex() {
		return regex;
	}

	public void setRegex(String regex) {
		this.regex = regex;
	}

	public String getPattern() {
		return pattern;
	}

	public void setPattern(String pattern) {
		this.pattern = pattern;
	}

	public UnaryOperator<String> getFormat() {
		return format;
	}

	public void setFormat(UnaryOperator<String> format) {
		this.format = format;
	}
}


class FileTransformer {
	private static final Logger LOG = LoggerFactory.getLogger(FileTransformer.class);
	private Path source = null;
	private Path destination = null;
	private List<String> entityCodes = new ArrayList<>();

	public FileTransformer() {
		super();
	}

	public FileTransformer(Path source, Path destination, List<String> entityCodes) {
		super();
		this.source = source;
		this.destination = destination;
		this.entityCodes = entityCodes;
	}

	public Path getSource() {
		return this.source;
	}

	public void setSource(Path source) {
		this.source = source;
	}

	public Path getDestination() {
		return this.destination;
	}

	public void setDestination(Path destination) {
		this.destination = destination;
	}

	public List<String> getEntityCodes() {
		return this.entityCodes;
	}

	public void setEntityCodes(List<String> entityCodes) {
		this.entityCodes = entityCodes;
	}

	private String searchAndReplace(List<Substitute> substitutes, String fileContent,
			String entityCode) {
		if (!substitutes.isEmpty()) {
			for (Substitute substitute : substitutes) {
				UnaryOperator<String> format = substitute.getFormat();
				String replacement = String.format(substitute.getPattern(), format.apply(entityCode));
				fileContent = fileContent.replaceAll(substitute.getRegex(), replacement);
			}
		}
		return fileContent;
	}

	public List<File> generateFiles(List<Substitute> substitutes) throws BusinessException {
		StringWriter writer = new StringWriter();
		List<File> generatedFiles = new ArrayList<>();
		try {
			IOUtils.copy(new InputStreamReader(new FileInputStream(this.source.toFile())), writer);
			String fileContent = writer.toString();
			for (String entityCode : this.entityCodes) {
				String outputContent = searchAndReplace(substitutes, fileContent, entityCode);
				String outputFileName =
						destination.toString().replace("Parent", WebAppScriptHelper.toPascalName(entityCode));
				LOG.debug("output file name: {}", outputFileName);
				File outputFile = new File(outputFileName);
				FileUtils.write(outputFile, outputContent, StandardCharsets.UTF_8);
				generatedFiles.add(outputFile);
			}
		} catch (IOException e) {
			throw new BusinessException("Failed loading js template with error: " + e.getMessage());
		}
		return generatedFiles;
	}
}


class FormFields {
	private static final Logger LOG = LoggerFactory.getLogger(FormFields.class);
	private String CRLF = WebAppScriptHelper.CRLF;
	private Set<FieldGroup> groups;

	public FormFields() {
		this.groups = new HashSet<>();
	}

	public void add(CustomFieldTemplate template) {
		Field field = new Field(template);
		FieldGroup newGroup = new FieldGroup(field);
		FieldGroup existingGroup =
				this.groups.stream().filter((group) -> group.equals(newGroup)).findFirst()
						.orElse(newGroup);
		existingGroup.add(field);
		this.groups.add(existingGroup);
	}

	@Override
	public String toString() {
		String prefix = "export const formFields = [" + CRLF;
		String suffix = CRLF + "];" + CRLF;
		return this.groups.stream().sorted().map(FieldGroup::toString)
				.collect(Collectors.joining(CRLF, prefix, suffix));
	}
}


class FieldGroup implements Comparable<FieldGroup> {
	private String CRLF = WebAppScriptHelper.CRLF;
	private String name;
	private int index;
	private List<Field> fields;

	public FieldGroup(Field field) {
		super();
		Map<String, String> guiPosition = field.getTemplate().getGuiPositionParsed();
		if (guiPosition != null && guiPosition.size() > 0) {
			this.index = Integer.parseInt(guiPosition.get("tab_pos"));
			this.name = WebAppScriptHelper.toTitleName(guiPosition.get("tab_name"));
		} else {
			this.index = 0;
			this.name = "";
		}
		this.fields = new ArrayList<>();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public int getIndex() {
		return index;
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public List<Field> getFields() {
		return fields;
	}

	public void setFields(List<Field> fields) {
		this.fields = fields;
	}

	public void add(Field field) {
		this.fields.add(field);
	}

	@Override
	public int compareTo(FieldGroup o) {
		return this.getIndex() - o.getIndex();
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof FieldGroup)) {
			return false;
		}
		FieldGroup fieldGroup = (FieldGroup) o;
		return Objects.equals(this.getName(), fieldGroup.getName())
				&& this.getIndex() == fieldGroup.getIndex();
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.name, this.index);
	}

	@Override
	public String toString() {
		StringBuilder content = new StringBuilder("\t{").append(CRLF).append("\t\tlabel: \"")
				.append(this.name)
				.append("\",").append(CRLF).append("\t\tfields: [").append(CRLF)
				.append(
						this.fields.stream().sorted().map(Field::toString).collect(Collectors.joining(CRLF)))
				.append(CRLF)
				.append("\t\t]").append(CRLF).append("\t},").append(CRLF);
		return content.toString();
	}
}


class Field implements Comparable<Field> {
	private static final Logger LOG = LoggerFactory.getLogger(Field.class);
	private static String NAME_SEPARATOR = " - ";
	private String CRLF = WebAppScriptHelper.CRLF;
	private int index;
	private String label;
	private CustomFieldTemplate template;

	public Field(CustomFieldTemplate template) {
		this.template = template;
		Map<String, String> guiPosition = template.getGuiPositionParsed();
		if (guiPosition != null && guiPosition.size() > 0) {
			this.index = Integer.parseInt(guiPosition.get("field_pos"));
		} else {
			this.index = 0;
		}
		this.label = WebAppScriptHelper.toTitleName(template.getCode());
	}

	public int getIndex() {
		return index;
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public CustomFieldTemplate getTemplate() {
		return template;
	}

	public void setTemplate(CustomFieldTemplate template) {
		this.template = template;
	}

	@Override
	public int compareTo(Field o) {
		return this.getIndex() - o.getIndex();
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof Field)) {
			return false;
		}
		Field field = (Field) o;
		return this.getIndex() == field.getIndex() && Objects.equals(this.getLabel(), field.getLabel());
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.index, this.label);
	}

	@Override
	public String toString() {
		StringBuilder fieldContents = new StringBuilder();
		CustomFieldTypeEnum type = template.getFieldType();

		Map<String, Object> fields =
				JacksonUtil.convert(template, new TypeReference<Map<String, Object>>() {});

		fields.put("label", WebAppScriptHelper.toTitleName(template.getCode()));

		if (type == CustomFieldTypeEnum.ENTITY || type == CustomFieldTypeEnum.CHILD_ENTITY) {
			String entityClass = template.getEntityClazz() != null ? template.getEntityClazz() : "";
			if (entityClass != null) {
				LOG.debug(String.format("entityClass: \"%s\"", entityClass));
				if (entityClass.contains(NAME_SEPARATOR)) {
					String[] entityName = entityClass.split(NAME_SEPARATOR);
					if (entityName != null) {
						fields.remove("entityClazz");
						if (entityName.length > 1) {
							fields.put("name", entityName[1]);
							fields.put("entitySchema", entityName[1]);
						} else {
							fields.put("entityClass", entityClass);
						}
					}
				}
			}
		}

		fields.remove("displayFormat");
		if (type == CustomFieldTypeEnum.DATE) {
			String displayFormat =
					template.getDisplayFormat() != null ? template.getDisplayFormat() : "YYYY/MM/dd";
			fields.put("displayFormat", displayFormat);
		}

		fieldContents.append("\t\t\t").append(JacksonUtil.toString(fields)).append(",");
		return fieldContents.toString();
	}
}


class EntityActions {
	private static final Logger LOG = LoggerFactory.getLogger(EntityActions.class);
	private String CRLF = WebAppScriptHelper.CRLF;
	private Set<Action> actions;

	public EntityActions() {
		this.actions = new HashSet<>();
	}

	public void add(EntityCustomAction customAction) {
		LOG.debug("adding customAction: {}", customAction);
		this.actions.add(new Action(customAction));
		LOG.debug("this.actions: {}", this.actions);
	}

	@Override
	public String toString() {
		LOG.debug("actions: {}", this.actions);
		String prefix = "export const actions = [" + CRLF;
		String suffix = CRLF + "];" + CRLF;
		return this.actions.stream().sorted().map(Action::toString)
				.collect(Collectors.joining(CRLF, prefix, suffix));
	}
}


class Action implements Comparable<Action> {
	private static final Logger LOG = LoggerFactory.getLogger(Action.class);
	private int index;
	private String label;
	private EntityCustomAction customAction;

	public Action(EntityCustomAction customAction) {
		this.customAction = customAction;
		Map<String, String> guiPosition = customAction.getGuiPositionParsed();
		LOG.debug("guiPosition: {}", customAction.getGuiPosition());
		if (guiPosition != null && guiPosition.size() > 0) {
			this.index = Integer.parseInt(guiPosition.get("field_pos"));
		} else {
			this.index = 0;
		}
	}

	public int getIndex() {
		return index;
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public EntityCustomAction getCustomAction() {
		return customAction;
	}

	public void setCustomAction(EntityCustomAction customAction) {
		this.customAction = customAction;
	}

	@Override
	public int compareTo(Action o) {
		return this.getIndex() - o.getIndex();
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof Action)) {
			return false;
		}
		Action action = (Action) o;
		return Objects.equals(this.getCustomAction().getCode(), action.getCustomAction().getCode())
				&& this.getIndex() == action.getIndex()
				&& Objects.equals(this.getLabel(), action.getLabel());
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.index, this.label);
	}

	@Override
	public String toString() {
		StringBuilder actionDetails = new StringBuilder();
		Map<String, Object> action = new HashMap<>();
		action.put("code", customAction.getCode());
		action.put("applicableOnEl", customAction.getApplicableOnEl());
		action.put("applicableToEntityInstance", customAction.getApplicableToEntityInstance());
		action.put("applicableToEntityList", customAction.getApplicableToEntityList());
		action.put("appliesTo", customAction.getAppliesTo());
		action.put("guiPosition", customAction.getGuiPosition());
		action.put("guiPositionParsed", customAction.getGuiPositionParsed());
		action.put("label", customAction.getLabel());
		action.put("labelI18nNullSafe", customAction.getLabelI18nNullSafe());
		action.put("script", customAction.getScript().getCode());
		action.put("scriptParameters", customAction.getScriptParameters());
		actionDetails.append("\t").append(JacksonUtil.toString(action)).append(",");
		return actionDetails.toString();
	}
}


class WebAppScriptHelper {
	private static final String WORD_REGEX = "(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_|\\s|-";
	private static final String EMPTY = "";
	private static final String SPACE = " ";
	private static final String DASH = "-";
	private static final String UNDERSCORE = "_";
	private static final UnaryOperator<String> TITLE_CASE = word -> word.isEmpty() ? word
			: Character.toTitleCase(word.charAt(0)) + word.substring(1).toLowerCase();
	private static final UnaryOperator<String> UPPER_CASE =
			word -> word.isEmpty() ? word : word.toUpperCase();
	private static final UnaryOperator<String> LOWER_CASE =
			word -> word.isEmpty() ? word : word.toLowerCase();

	public static final String CRLF = "\r\n";

	private static final String convert(String input, UnaryOperator<String> mapper,
			String joinCharacter) {
		if (input == null || input.isEmpty()) {
			return "";
		}
		Pattern pattern = Pattern.compile(WORD_REGEX);
		Matcher matcher = pattern.matcher(input);
		String text = matcher.replaceAll(SPACE);
		String[] words = text.split(SPACE);
		return Arrays.stream(words).map(mapper).collect(Collectors.joining(joinCharacter));
	}

	static final String toTitleName(String input) {
		return convert(input, TITLE_CASE, SPACE);
	}

	static final UnaryOperator<String> TITLE = WebAppScriptHelper::toTitleName;

	static final String toConstantName(String input) {
		return convert(input, UPPER_CASE, UNDERSCORE);
	}

	static final UnaryOperator<String> CONSTANT = WebAppScriptHelper::toConstantName;

	static final String toVariableName(String input) {
		String name = convert(input, TITLE_CASE, EMPTY);
		return Character.toLowerCase(name.charAt(0)) + name.substring(1);
	}

	static final UnaryOperator<String> VARIABLE = WebAppScriptHelper::toVariableName;

	static final String toPascalName(String input) {
		String name = convert(input, TITLE_CASE, EMPTY);
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	static final UnaryOperator<String> PASCAL = WebAppScriptHelper::toPascalName;

	static final String toTagName(String input) {
		return convert(input, LOWER_CASE, DASH);
	}

	static final UnaryOperator<String> TAG = WebAppScriptHelper::toTagName;
}
