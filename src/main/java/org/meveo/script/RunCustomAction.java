package org.meveo.script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.meveo.admin.exception.BusinessException;
import org.meveo.api.exception.EntityDoesNotExistsException;
import org.meveo.elresolver.ELException;
import org.meveo.model.crm.custom.EntityCustomAction;
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.ICustomFieldEntity;
import org.meveo.model.IEntity;
import org.meveo.model.persistence.CEIUtils;
import org.meveo.model.persistence.JacksonUtil;
import org.meveo.model.storage.Repository;
import org.meveo.persistence.CrossStorageService;
import org.meveo.service.base.MeveoValueExpressionWrapper;
import org.meveo.service.custom.EntityCustomActionService;
import org.meveo.service.custom.CustomEntityTemplateService;
import org.meveo.service.script.CustomScriptService;
import org.meveo.service.script.Script;
import org.meveo.service.storage.RepositoryService;
import org.meveo.service.script.ScriptInstanceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RunCustomAction extends Script {
  private static final String MESSAGE = "message";
  private static final Logger LOG = LoggerFactory.getLogger(RunCustomAction.class);
  private EntityCustomActionService ecaService = getCDIBean(EntityCustomActionService.class);
  private RepositoryService repositoryService = getCDIBean(RepositoryService.class);
  private ScriptInstanceService scriptInstanceService = getCDIBean(ScriptInstanceService.class);
  private CrossStorageService crossStorageService = getCDIBean(CrossStorageService.class);
  private CustomEntityTemplateService cetService = getCDIBean(CustomEntityTemplateService.class);

  private Repository repository;
  private String actionCode;
  private EntityList runWith;
  private Map<String, Object> result;

  public String getActionCode() {
    return actionCode;
  }

  public void setActionCode(String actionCode) {
    this.actionCode = actionCode;
  }

  public EntityList getRunWith() {
    return runWith;
  }

  public void setRunWith(EntityList runWith) {
    this.runWith = runWith;
  }

  public Repository getDefaultRepository() {
    if (repository == null) {
      repository = repositoryService.findDefaultRepository();
    }
    return repository;
  }

  public Map<String, Object> getResult() {
    return result;
  }

  RunCustomAction() {
    super();
    this.actionCode = "";
    this.runWith = null;
    this.result = new HashMap<>();
  }

  @Override
  public void execute(Map<String, Object> parameters) throws BusinessException {
    super.execute(parameters);
    LOG.debug("parameters: {}", parameters);
    if (this.actionCode == null || this.actionCode.isEmpty()) {
      throw new BusinessException("No action code specified. Please specify an action code in the parameters.");
    }
    if (this.runWith == null) {
      throw new BusinessException(
          "No entity codes specified. Please specify at least one entity in the parameters.");
    }
    for (EntityParameter entityParameter : this.runWith.getEntities()) {
      Map<String, Object> singleResult = new HashMap<>();
      try {
        String entityCode = entityParameter.getCode();
        String uuid = entityParameter.getUuid();
        CustomEntityTemplate entityTemplate = cetService.findByCodeOrDbTablename(entityCode);
        Map<String, Object> entity = crossStorageService
            .find(this.getDefaultRepository(), entityTemplate, uuid, true);
        if (entity == null) {
          throw new BusinessException("Entity not found: " + entityParameter);
        }
        LOG.debug("entity: {}", entity);
        LOG.debug("this.actionCode: {}", this.actionCode);
        LOG.debug("this.entityParameter: {}", entityParameter);
        LOG.debug("uuid: {}", uuid);

        EntityCustomAction action = ecaService.findByCode(this.actionCode);
        if (action == null) {
          throw new BusinessException("Action not found: " + this.actionCode);
        }
        Map<String, Object> context = new HashMap<>();
        context.put(Script.CONTEXT_ACTION, this.actionCode);
        Map<Object, Object> elContext = new HashMap<>(context);
        elContext.put("entity", entity);

        LOG.debug("action: {}", action);
        LOG.debug("context: {}", context);
        LOG.debug("elContext: {}", elContext);

        action.getScriptParameters().forEach((key, value) -> {
          try {
            context.put(key, MeveoValueExpressionWrapper
                .evaluateExpression(value, elContext, Object.class));
          } catch (ELException e) {
            LOG.error("Failed to evaluate el for custom action", e);
          }
        });

        Map<String, Object> scriptResult = scriptInstanceService
            .execute(
                (IEntity) CEIUtils.pojoToCei(entity),
                this.getDefaultRepository(),
                action.getScript().getCode(),
                context);

        // Display a message accordingly on what is set in result
        if (scriptResult.containsKey(Script.RESULT_GUI_MESSAGE_KEY)) {
          singleResult.put(MESSAGE, (String) scriptResult.get(Script.RESULT_GUI_MESSAGE_KEY));
        } else if (scriptResult.containsKey(Script.RESULT_GUI_MESSAGE)) {
          singleResult.put(MESSAGE, (String) scriptResult.get(Script.RESULT_GUI_MESSAGE));
        } else {
          singleResult.put(MESSAGE, "Action executed successfully");
        }

        if (scriptResult.containsKey(Script.RESULT_GUI_OUTCOME)) {
          singleResult.put("value", (String) scriptResult.get(Script.RESULT_GUI_OUTCOME));
        }
        result.put(entityCode + ":" + uuid, singleResult);
      } catch (EntityDoesNotExistsException e) {
        LOG.error("Failed to execute a script {} on entity {}", this.actionCode, entityParameter,
            e);
        throw new BusinessException(
            "Failed to execute custom action: " + this.actionCode + " on entity: "
                + entityParameter,
            e);
      } finally {
        LOG.info("Run " + this.actionCode + " action done.");
      }
    }
  }
}

class EntityList {
  private List<EntityParameter> entities;

  public List<EntityParameter> getEntities() {
    return entities;
  }

  public void setEntities(List<EntityParameter> entities) {
    this.entities = entities;
  }
}
class EntityParameter {
  private String code;
  private String uuid;

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getUuid() {
    return uuid;
  }

  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  @Override
  public String toString() {
    return "EntityParameter [code=" + code + ", uuid=" + uuid + "]";
  }
}
