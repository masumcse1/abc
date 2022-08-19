package org.meveo.script;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.meveo.admin.exception.BusinessException;
import org.meveo.commons.utils.ParamBean;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
import org.meveo.security.MeveoUser;
import org.meveo.service.admin.impl.MeveoModuleService;
import org.meveo.service.crm.impl.CurrentUserProducer;
import org.meveo.service.script.Script;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetModelsScript extends Script {
	public static final String CRLF = "\r\n";
	public static final String TAB = "\t";
	private static final String LOCALHOST = "http://localhost:8080/";
	private static final String IMPORT_STATEMENT = "import * as %s from \"%s/%s.js\";";
	private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();
	private static final String AFFIX = "-UI";
	private static final Logger LOG = LoggerFactory.getLogger(GetModelsScript.class);

	private MeveoModuleService meveoModuleService = getCDIBean(MeveoModuleService.class);
	private ParamBeanFactory paramBeanFactory = getCDIBean(ParamBeanFactory.class);
	private CurrentUserProducer currentUserProducer = getCDIBean(CurrentUserProducer.class);

	private String moduleCode;
	private Object result;

	public void setModuleCode(String moduleCode) {
		this.moduleCode = moduleCode;
	}

	public Object getResult() {
		return result;
	}

	@Override
	public void execute(Map<String, Object> parameters) throws BusinessException {
		LOG.debug("START - GetModelsScript.execute()");
		super.execute(parameters);
		LOG.debug("moduleCode: {}", moduleCode);
		LOG.debug("parameters: {}", parameters);
		if (moduleCode == null) {
			throw new BusinessException("moduleCode not set");
		}
		MeveoModule module = meveoModuleService.findByCode(moduleCode);
		MeveoUser user = currentUserProducer.getCurrentUser();
		ParamBean appConfig = paramBeanFactory.getInstance();

		String appContext = appConfig.getProperty("meveo.admin.webContext", "");
		String serverUrl = appConfig.getProperty("meveo.admin.baseUrl", null);

		String baseUrl = serverUrl;
		if (baseUrl == null) {
			baseUrl = LOCALHOST;
		}

		baseUrl = baseUrl.strip().endsWith("/") ? baseUrl : baseUrl + "/";
		baseUrl = baseUrl + appContext;

		String schemaPath = String.format("%s/rest/webapp/%s/model", baseUrl, moduleCode);

		LOG.debug("user: {}", user);

		if (module != null) {
			LOG.debug("Module found: {}", module.getCode());
			Set<MeveoModuleItem> moduleItems = module.getModuleItems();
			List<String> entityCodes = moduleItems.stream()
					.filter(item -> CUSTOM_TEMPLATE.equals(item.getItemClass()))
					.map(entity -> entity.getItemCode())
					.collect(Collectors.toList());
			LOG.debug("entityCodes: {}", entityCodes);

			try {
				// using user role and permissions, figure out which entities are allowed to be exported
				LOG.debug("user.getRoles(): {}", user.getRoles());
				List<String> permissions = user.getRoles().stream().filter(role -> role.startsWith("CE_"))
						.collect(Collectors.toList());
				LOG.debug("permissions: {}", permissions);

				List<String> allowedEntities =
						entityCodes.stream()
								.filter(entityCode -> permissions.stream()
										.anyMatch(permission -> permission.contains(entityCode)))
								.collect(Collectors.toList());
				LOG.debug("allowedEntities: {}", allowedEntities);

				List<EntityPermission> entityPermissions = allowedEntities.stream()
						.map(entityCode -> {
							List<String> permissionList = permissions.stream()
									.filter(permission -> permission.contains(entityCode))
									.map(permission -> permission.substring(permission.indexOf("-") + 1))
									.sorted()
									.collect(Collectors.toList());
							return new EntityPermission(entityCode, permissionList);
						})
						.collect(Collectors.toList());
				LOG.debug("entityPermissions: {}", entityPermissions);

				// generate model index.js
				StringBuilder modelIndexImports = new StringBuilder();

				for (String entityCode : allowedEntities) {
					String modelImport = String.format(IMPORT_STATEMENT, entityCode, schemaPath, entityCode);
					LOG.debug("modelImport: {}", modelImport);
					modelIndexImports.append(modelImport).append(CRLF);
				}
				modelIndexImports
						.append(CRLF)
						.append("export const MODELS = [ ")
						.append(CRLF)
						.append(TAB)
						.append(String.join(", ", allowedEntities))
						.append(CRLF)
						.append(" ];")
						.append(CRLF);

				modelIndexImports
						.append(CRLF)
						.append("export const ENTITY_PERMISSIONS = { ");

				for (EntityPermission entityPermission : entityPermissions) {
					String availablePermissions = String.join(", ", entityPermission.getPermissions().stream()
							.map(permission -> "\"" + permission + "\"")
							.collect(Collectors.toList()));
					modelIndexImports
							.append(CRLF)
							.append(TAB)
							.append(entityPermission.getEntityCode())
							.append(": [ ")
							.append(CRLF)
							.append(TAB)
							.append(TAB)
							.append(availablePermissions)
							.append(CRLF)
							.append(TAB)
							.append(" ], ");
				}
				modelIndexImports.append(CRLF).append("};").append(CRLF);

				// return model index.js
				result = modelIndexImports.toString();

			} catch (Exception exception) {
				throw new BusinessException(exception);
			}
		} else {
			throw new BusinessException("Module not found: " + moduleCode);
		}
		LOG.debug("END - GetModelsScript.execute()");
	}
}


class EntityPermission {
	private String entityCode;
	private List<String> permissions;

	public EntityPermission(String entityCode, List<String> permissions) {
		this.entityCode = entityCode;
		this.permissions = permissions;
	}

	public String getEntityCode() {
		return entityCode;
	}

	public void setEntityCode(String entityCode) {
		this.entityCode = entityCode;
	}

	public List<String> getPermissions() {
		return permissions;
	}

	public void setPermissions(List<String> permissions) {
		this.permissions = permissions;
	}

	@Override
	public String toString() {
		return "EntityPermission [entityCode=" + entityCode + ", permissions=" + permissions + "]";
	}
}
