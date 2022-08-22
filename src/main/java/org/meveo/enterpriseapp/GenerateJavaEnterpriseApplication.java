package org.meveo.enterpriseapp;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.meveo.admin.exception.BusinessException;
import org.meveo.api.persistence.CrossStorageApi;
import org.meveo.commons.utils.ParamBean;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.JavaEnterpriseApp;
import org.meveo.model.git.GitRepository;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
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

public class GenerateJavaEnterpriseApplication extends Script {

	private static final Logger log = LoggerFactory.getLogger(GenerateJavaEnterpriseApplication.class);

	private static final String MASTER_BRANCH = "master";

	private static final String MEVEO_BRANCH = "meveo";

	private static final String MV_TEMPLATE_REPO = "https://github.com/meveo-org/mv-template-1.git";

	private static final String LOG_SEPARATOR = "***********************************************************";

	private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();

	private static final String WEB_APP_TEMPLATE = JavaEnterpriseApp.class.getSimpleName();

	private static final String PARENT = "Parent";

	private static final String PAGE_TEMPLATE = "Parent.js";

	private static final String INDEX_TEMPLATE = "index.js";

	private static final String LOCALHOST = "http://localhost:8080/";

	private static final String KEYCLOAK_URL = "http://host.docker.internal:8081/auth";

	private static final String KEYCLOAK_REALM = "meveo";

	private static final String KEYCLOAK_RESOURCE = "meveo-web";

	private static final String MODULE_CODE = "MODULE_CODE";

	private static final String AFFIX = "-UI";
	
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
		log.info("generating java enterprise application from module, {}", moduleCode);
		log.debug("START - GenerateJavaEnterpriseApplication.execute()");

		super.execute(parameters);

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
		log.info("generating java enterprise application from module, {}", moduleCode);
		log.debug("baseUrl: {}", baseUrl);
		if (module != null) {
			log.debug("Module found: {}", module.getCode());
			Set<MeveoModuleItem> moduleItems = module.getModuleItems();
			log.debug("CUSTOM_TEMPLATE={}", CUSTOM_TEMPLATE);
			List<String> entityCodes = moduleItems.stream().filter(item -> CUSTOM_TEMPLATE.equals(item.getItemClass()))
					.map(entity -> entity.getItemCode()).collect(Collectors.toList());
			log.debug("entityCodes: {}", entityCodes);
			JavaEnterpriseApp webapp = crossStorageApi.find(getDefaultRepository(), JavaEnterpriseApp.class)
					.by("code", module.getCode()).getResult();

			// SAVE COPY OF MV-TEMPLATE TO MEVEO GIT REPOSITORY
			GitRepository webappTemplateRepo = gitRepositoryService.findByCode(WEB_APP_TEMPLATE);
			if (webappTemplateRepo == null) {
				log.debug("CREATE NEW GitRepository: {}", WEB_APP_TEMPLATE);
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
			log.debug("webappTemplate path: {}", webappTemplatePath.toString());

			GitRepository moduleWebAppRepo = gitRepositoryService.findByCode(moduleCode);

			if (moduleWebAppRepo == null) {
				moduleWebAppRepo = new GitRepository();
				moduleWebAppRepo.setCode(moduleCode + AFFIX);
				// moduleWebAppRepo.setDescription(WebAppScriptHelper.toTitleName(moduleCode) +
				// " Template repository");
				moduleWebAppRepo.setRemoteOrigin(remoteUrl);
				moduleWebAppRepo.setDefaultRemoteUsername(remoteUsername);
				moduleWebAppRepo.setDefaultRemotePassword(remotePassword);
				gitRepositoryService.create(moduleWebAppRepo);
			}
			gitClient.checkout(moduleWebAppRepo, MEVEO_BRANCH, true);
			String moduleWebAppBranch = gitClient.currentBranch(moduleWebAppRepo);
			File moduleWebAppDirectory = GitHelper.getRepositoryDir(user, moduleCode);
			Path moduleWebAppPath = moduleWebAppDirectory.toPath();
			log.debug("===============working.==============================================");
			
			System.out.println("moduleWebAppPath"+moduleWebAppPath);
			
			
			
		}
		log.debug("END - GenerateJavaEnterpriseApplication.execute()--------------");
	}

	void generateDto() {

	}

	void generateEndPoint() {

	}

}
