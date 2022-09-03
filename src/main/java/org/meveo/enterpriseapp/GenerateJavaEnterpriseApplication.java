package org.meveo.enterpriseapp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.ws.rs.PathParam;

import org.apache.commons.io.FileUtils;
import org.meveo.admin.exception.BusinessException;
import org.meveo.api.persistence.CrossStorageApi;
import org.meveo.commons.utils.MeveoFileUtils;
import org.meveo.commons.utils.ParamBean;
import org.meveo.commons.utils.ParamBeanFactory;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.customEntities.JavaEnterpriseApp;
import org.meveo.model.git.GitRepository;
import org.meveo.model.module.MeveoModule;
import org.meveo.model.module.MeveoModuleItem;
import org.meveo.model.storage.Repository;
import org.meveo.model.technicalservice.endpoint.Endpoint;
import org.meveo.persistence.CrossStorageService;
import org.meveo.security.MeveoUser;
import org.meveo.service.admin.impl.MeveoModuleService;
import org.meveo.service.crm.impl.CustomFieldInstanceService;
import org.meveo.service.crm.impl.CustomFieldTemplateService;
import org.meveo.service.crm.impl.JSONSchemaGenerator;
import org.meveo.service.crm.impl.JSONSchemaIntoJavaClassParser;
import org.meveo.service.custom.CustomEntityTemplateService;
import org.meveo.service.custom.EntityCustomActionService;
import org.meveo.service.git.GitClient;
import org.meveo.service.git.GitHelper;
import org.meveo.service.git.GitRepositoryService;
import org.meveo.service.script.Script;
import org.meveo.service.storage.RepositoryService;
import org.meveo.service.technicalservice.endpoint.EndpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

public class GenerateJavaEnterpriseApplication extends Script {

	@Inject
	private JSONSchemaIntoJavaClassParser jSONSchemaIntoJavaClassParser;

	@Inject
	private JSONSchemaGenerator jSONSchemaGenerator;

	private Map<String, Object> jsonMap;

	///////////////////////////////////////////

	private static final Logger log = LoggerFactory.getLogger(GenerateJavaEnterpriseApplication.class);

	private static final String MASTER_BRANCH = "master";

	private static final String MEVEO_BRANCH = "meveo";

	private static final String MV_TEMPLATE_REPO = "https://github.com/meveo-org/mv-template-1.git";

	private static final String LOG_SEPARATOR = "***********************************************************";

	private static final String CUSTOM_TEMPLATE = CustomEntityTemplate.class.getName();
	
	private static final String CUSTOM_ENDPOINT_TEMPLATE = Endpoint.class.getName();

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
	
	@Inject
    private EndpointService endpointService;

	private Repository repository;

	private String moduleCode;
	
	private String entityClass ;//= "Product";
	private String dtoClass ;//= "ProductDto";
	private String serviceCode ;//= "CreateMyProduct";
	private String injectedFieldName;// ="CreateMyProduct";
	private String httpMethod;// ="CreateMyProduct";
	private String pathParameter;
	private String httpBasePath;

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

			GitRepository moduleWebAppRepo = gitRepositoryService.findByCode(moduleCode);

			gitClient.checkout(moduleWebAppRepo, MEVEO_BRANCH, true);
			String moduleWebAppBranch = gitClient.currentBranch(moduleWebAppRepo);
			File moduleWebAppDirectory = GitHelper.getRepositoryDir(user, moduleCode);
			Path moduleWebAppPath = moduleWebAppDirectory.toPath();
			log.debug("===============working. for DTO Generation==============================================");
			 List<File> filesToCommit = new ArrayList<>();
			

			//compilationUnit.addImport("org.meveo.model.customEntities." + entityCodes.get(0));
			// jsonMap.put("type", "String");
			
			
			    String pathJavaDtoFile = "facets/java/org/meveo/model/DTO/" + entityCodes.get(0)+"DTO"+ ".java";
	            
	            try {
					
					File dtofile = new File (moduleWebAppDirectory, pathJavaDtoFile);
					String dtocontent = generateDto(jsonMap, entityCodes);
					FileUtils.write(dtofile, dtocontent, StandardCharsets.UTF_8);
					filesToCommit.add(dtofile);
				} catch (IOException e) {
					throw new BusinessException("Failed creating file." + e.getMessage());
				}
			
			
			log.debug("===============working. for End point Generation==============================================");
	    	
			List<String> endpointlist = moduleItems.stream().filter(item -> CUSTOM_ENDPOINT_TEMPLATE.equals(item.getItemClass()))
					.map(entity -> entity.getItemCode()).collect(Collectors.toList());
					
			List<Endpoint> enpointlists= endpointService.findByServiceCode(endpointlist.get(0));
	     
			    entityClass   = entityCodes.get(0);
		        dtoClass      = entityCodes.get(0) + "Dto";
		        log.debug("entityCodes: {}", entityCodes);
		       
		     
	        for (String endpointstr :endpointlist) {
	        	Endpoint endpoint = endpointService.findByCode(endpointstr);
	        	
	        	serviceCode   = getServiceCode(endpoint.getService().getCode()) ;
		        httpMethod    = endpoint.getMethod().getLabel();
		        pathParameter = endpoint.getPath();
		        httpBasePath  = endpoint.getBasePath();
		        injectedFieldName = getNonCapitalizeName(serviceCode);
			
            
            File gitDirectory = GitHelper.getRepositoryDir(user, module.getGitRepository().getCode());
            String pathJavaFile = "facets/java/org/meveo/model/customEndPoint/" + getRestClassName(entityClass, httpMethod) + ".java";
            
            try {
				
				File outputFile = new File (gitDirectory, pathJavaFile);
				String fullContent = generateEndPoint(endpoint,entityCodes);
				FileUtils.write(outputFile, fullContent, StandardCharsets.UTF_8);
				filesToCommit.add(outputFile);
			} catch (IOException e) {
				throw new BusinessException("Failed creating file." + e.getMessage());
			}
            
            if (!filesToCommit.isEmpty()) {
				gitClient.commitFiles(moduleWebAppRepo, filesToCommit, "Initialize local commits test");
			}
            
	        }
	        
             
		}
		log.debug("END - GenerateJavaEnterpriseApplication.execute()--------------");
	}

	String generateDto(Map<String, Object> jsonMap, List<String> entityCodes) {
		CompilationUnit compilationUnit = new CompilationUnit();
		ClassOrInterfaceDeclaration classDeclaration = compilationUnit.addClass(entityCodes.get(0) + "Dto")
				.setPublic(true);

		FieldDeclaration field1 = new FieldDeclaration();
		VariableDeclarator variable1 = new VariableDeclarator();
		variable1.setName("type");
		variable1.setType("String");

		field1.setModifiers(Modifier.Keyword.PRIVATE);
		field1.addVariable(variable1);
		classDeclaration.addMember(field1);

		FieldDeclaration field2 = new FieldDeclaration();
		VariableDeclarator variable2 = new VariableDeclarator();
		variable2.setName(entityCodes.get(0).toLowerCase());
		variable2.setType(entityCodes.get(0));

		field2.setModifiers(Modifier.Keyword.PRIVATE);
		field2.addVariable(variable2);
		classDeclaration.addMember(field2);

		field1.createGetter();
		field1.createSetter();
		field2.createGetter();
		field2.createSetter();

		classDeclaration.addConstructor(Modifier.Keyword.PUBLIC);
		classDeclaration.addConstructor();

		VariableDeclarator variableDeclarator1 = new VariableDeclarator();
		variableDeclarator1.setType(entityCodes.get(0));

		VariableDeclarator variableDeclarator2 = new VariableDeclarator();
		variableDeclarator2.setType("String");

		classDeclaration.addConstructor(Modifier.Keyword.PUBLIC)
				.addParameter(new Parameter(variableDeclarator1.getType(), "product"))
				.addParameter(new Parameter(variableDeclarator2.getType(), "type"))
				.setBody(JavaParser.parseBlock("{\n this.product = product; \n  this.type = type; \n}"));

	
		return compilationUnit.toString();

	}
 
	String generateEndPoint( Endpoint endpoint,List<String> entityCodes) {
		
	    CompilationUnit cu = new CompilationUnit();
		cu.setPackageDeclaration("org.meveo.mymodule.resource");
		
		cu.getImports().add(new ImportDeclaration(new Name("java.io"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("java.util"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("javax.ws.rs"), false, true));
		cu.getImports().add(new ImportDeclaration(new Name("javax.enterprise.context.RequestScoped"), false, false));
		cu.getImports().add(new ImportDeclaration(new Name("javax.inject.Inject"), false, false));
		
		cu.getImports().add(new ImportDeclaration(new Name("org.meveo.admin.exception.BusinessException"), false, false));
		cu.getImports().add(new ImportDeclaration(new Name("org.meveo.mymodule.dto.CustomEndpointResource"), false, false));
		cu.getImports().add(new ImportDeclaration(new Name("org.meveo.mymodule.dto."+dtoClass), false, false));
		cu.getImports().add(new ImportDeclaration(new Name(endpoint.getService().getCode()), false, false));
	

		ClassOrInterfaceDeclaration clazz=generateRestClass(cu);

		MethodDeclaration restMethod = generateRestMethod(clazz);

		BlockStmt beforeTryblock = new BlockStmt();

		VariableDeclarator var_result = new VariableDeclarator();
		var_result.setName("result");
		var_result.setType("String");
		var_result.setInitializer(new NullLiteralExpr());

		NodeList<VariableDeclarator> var_result_declarator = new NodeList<>();
		var_result_declarator.add(var_result);
		beforeTryblock.addStatement(new ExpressionStmt().setExpression(new VariableDeclarationExpr().setVariables(var_result_declarator)));

		beforeTryblock.addStatement(new ExpressionStmt(new NameExpr("parameterMap = new HashMap<String, Object>()")));

		if(httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT")) {
			
		MethodCallExpr getEntity_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
		getEntity_methodCall.addArgument(new StringLiteralExpr(getNonCapitalizeName(entityClass)));
		getEntity_methodCall.addArgument(new MethodCallExpr(new NameExpr(getNonCapitalizeName(dtoClass)), "get" + entityClass));

		beforeTryblock.addStatement(getEntity_methodCall);

		MethodCallExpr getType_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
		getType_methodCall.addArgument(new StringLiteralExpr("type"));
		getType_methodCall.addArgument(new MethodCallExpr(new NameExpr(getNonCapitalizeName(dtoClass)), "getType"));

		beforeTryblock.addStatement(getType_methodCall);
		}
		
		if(httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("DELETE") || httpMethod.equalsIgnoreCase("PUT")) {
			MethodCallExpr getType_methodCall = new MethodCallExpr(new NameExpr("parameterMap"), "put");
			getType_methodCall.addArgument(new StringLiteralExpr("uuid"));
			getType_methodCall.addArgument("uuid");

			beforeTryblock.addStatement(getType_methodCall);
		}
		beforeTryblock.addStatement(new ExpressionStmt(new NameExpr("setRequestResponse()")));
		Statement trystatement = generateTryBlock(var_result);

		beforeTryblock.addStatement(trystatement);
		restMethod.setBody(beforeTryblock);    

		restMethod.getBody().get().getStatements().add(getReturnType());
			return cu.toString();
	}
	
	
	  private ClassOrInterfaceDeclaration generateRestClass(CompilationUnit cu) {
		ClassOrInterfaceDeclaration clazz = cu.addClass(getRestClassName(entityClass, httpMethod),	Modifier.Keyword.PUBLIC);
		clazz.addSingleMemberAnnotation("Path", new StringLiteralExpr(httpBasePath));
		clazz.addMarkerAnnotation("RequestScoped");
		var injectedfield = clazz.addField(serviceCode, injectedFieldName, Modifier.Keyword.PRIVATE);
		injectedfield.addMarkerAnnotation("Inject");
			
		NodeList<ClassOrInterfaceType> extendsList = new NodeList<>();
		extendsList.add(new ClassOrInterfaceType().setName(new SimpleName("CustomEndpointResource")));
		clazz.setExtendedTypes(extendsList);
	    return clazz;
	  }
	 
	private MethodDeclaration generateRestMethod(ClassOrInterfaceDeclaration clazz) {
		MethodDeclaration restMethod = clazz.addMethod(getRestMethodName(entityClass, httpMethod),	Modifier.Keyword.PUBLIC);
		restMethod.setType("Response");
		restMethod.addMarkerAnnotation(httpMethod);
		
		if(httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("DELETE") || httpMethod.equalsIgnoreCase("PUT")) {
		 restMethod.addSingleMemberAnnotation("Path", new StringLiteralExpr("/{uuid}"));
		}
		
		if(httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT")) {
			restMethod.addParameter(dtoClass, getNonCapitalizeName(dtoClass));
		}
				
		if(httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("DELETE") || httpMethod.equalsIgnoreCase("PUT")) {
			 Parameter restMethodParameter = new Parameter();
		     restMethodParameter.setType("String");
			 restMethodParameter.setName(getNonCapitalizeName("uuid"));
			 restMethodParameter.addSingleMemberAnnotation("PathParam", new StringLiteralExpr("uuid"));
			 restMethod.addParameter(restMethodParameter);
	}		
		
		
		
		restMethod.addSingleMemberAnnotation("Produces", "MediaType.APPLICATION_JSON");
		restMethod.addSingleMemberAnnotation("Consumes", "MediaType.APPLICATION_JSON");
		restMethod.addThrownException(IOException.class);
		restMethod.addThrownException(ServletException.class);
      return restMethod;

	}
	
	private Statement generateTryBlock(VariableDeclarator assignmentVariable) {
		BlockStmt tryblock = new BlockStmt();
		
		if(httpMethod.equalsIgnoreCase("POST") || httpMethod.equalsIgnoreCase("PUT"))
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "set" + entityClass).addArgument(new MethodCallExpr(new NameExpr(getNonCapitalizeName(dtoClass)), "get" + entityClass)));
		
		if(httpMethod.equalsIgnoreCase("GET") || httpMethod.equalsIgnoreCase("PUT") ||  httpMethod.equalsIgnoreCase("DELETE") )
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "setUuid").addArgument("uuid"));
			
		
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "init").addArgument("parameterMap"));
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "execute").addArgument("parameterMap"));
		tryblock.addStatement(new MethodCallExpr(new NameExpr(injectedFieldName), "finalize").addArgument("parameterMap"));
		tryblock.addStatement(assignment(assignmentVariable.getNameAsString(), injectedFieldName, "getResult"));
		Statement trystatement = addingException(tryblock);
		return trystatement;
	}

	private ReturnStmt getReturnType() {
		return new ReturnStmt(new NameExpr("Response.status(Response.Status.OK).entity(result).build()"));
	}
	
	private  String getServiceCode(String serviceCode) {
		return serviceCode.substring(serviceCode.lastIndexOf(".") + 1);
	}
	
	private  String getRestClassName(String entityClass, String httpMethod) {
		String className = null;
		if (httpMethod.equals("POST")) {
			className = entityClass + "Create";
		}else if(httpMethod.equals("GET")) {
			className = entityClass + "Get";
		}else if(httpMethod.equals("PUT")) {
			className = entityClass + "Update";
		}else if(httpMethod.equals("DELETE")) {
			className = entityClass + "Delete";
		}
		
		
		return className;
	}

	private  String getRestMethodName(String entityClass, String httpMethod) {
		String methodName = null;
		
		if (httpMethod.equals("POST")) {
			methodName = "save" + entityClass;
		}else if(httpMethod.equals("GET")) {
			methodName = "get" + entityClass;
		}else if(httpMethod.equals("PUT")) {
			methodName = "update" + entityClass;
		}else if(httpMethod.equals("DELETE")) {
			methodName = "remove" + entityClass;
		}
		return methodName;
	}

	private  Statement addingException(BlockStmt body) {
		TryStmt ts = new TryStmt();
		ts.setTryBlock(body);
		CatchClause cc = new CatchClause();
		String exceptionName = "e";
		cc.setParameter(new Parameter().setName(exceptionName).setType(BusinessException.class));
		BlockStmt cb = new BlockStmt();
		cb.addStatement(new ExpressionStmt(
				new NameExpr("return Response.status(Response.Status.BAD_REQUEST).entity(result).build()")));
		// cb.addStatement(new ThrowStmt(new NameExpr(ex8uj  ceptionName)));
		cc.setBody(cb);
		ts.setCatchClauses(new NodeList<>(cc));
	
		return ts;
	}

	private  Statement assignment(String assignOject, String callOBject, String methodName) {
		MethodCallExpr methodCallExpr = new MethodCallExpr(new NameExpr(callOBject), methodName);
		AssignExpr assignExpr = new AssignExpr(new NameExpr(assignOject), methodCallExpr, AssignExpr.Operator.ASSIGN);
		return new ExpressionStmt(assignExpr);
	}

	private  String getNonCapitalizeName(String className) {
		if (className == null || className.length() == 0)
			return className;
		String objectReferenceName = className.substring(0, 1).toLowerCase() + className.substring(1);
		return objectReferenceName;

	}

}
