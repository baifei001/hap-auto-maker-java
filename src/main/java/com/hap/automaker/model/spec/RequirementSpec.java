package com.hap.automaker.model.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * workflow_requirement_v1 规格定义
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RequirementSpec {

    @JsonProperty("schema_version")
    private String schemaVersion = "workflow_requirement_v1";

    @JsonProperty("meta")
    private SpecMeta meta = new SpecMeta();

    @JsonProperty("app")
    private AppSpec app = new AppSpec();

    @JsonProperty("worksheets")
    private WorksheetsSpec worksheets = new WorksheetsSpec();

    @JsonProperty("views")
    private ViewsSpec views = new ViewsSpec();

    @JsonProperty("view_filters")
    private ViewFiltersSpec viewFilters = new ViewFiltersSpec();

    @JsonProperty("roles")
    private RolesSpec roles = new RolesSpec();

    @JsonProperty("mock_data")
    private MockDataSpec mockData = new MockDataSpec();

    @JsonProperty("chatbots")
    private ChatbotsSpec chatbots = new ChatbotsSpec();

    @JsonProperty("pages")
    private PagesSpec pages = new PagesSpec();

    @JsonProperty("delete_default_views")
    private DeleteDefaultViewsSpec deleteDefaultViews = new DeleteDefaultViewsSpec();

    @JsonProperty("execution")
    private ExecutionSpec execution = new ExecutionSpec();

    // Getters and Setters
    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public SpecMeta getMeta() {
        return meta;
    }

    public void setMeta(SpecMeta meta) {
        this.meta = meta;
    }

    public AppSpec getApp() {
        return app;
    }

    public void setApp(AppSpec app) {
        this.app = app;
    }

    public WorksheetsSpec getWorksheets() {
        return worksheets;
    }

    public void setWorksheets(WorksheetsSpec worksheets) {
        this.worksheets = worksheets;
    }

    public ViewsSpec getViews() {
        return views;
    }

    public void setViews(ViewsSpec views) {
        this.views = views;
    }

    public ViewFiltersSpec getViewFilters() {
        return viewFilters;
    }

    public void setViewFilters(ViewFiltersSpec viewFilters) {
        this.viewFilters = viewFilters;
    }

    public RolesSpec getRoles() {
        return roles;
    }

    public void setRoles(RolesSpec roles) {
        this.roles = roles;
    }

    public MockDataSpec getMockData() {
        return mockData;
    }

    public void setMockData(MockDataSpec mockData) {
        this.mockData = mockData;
    }

    public ChatbotsSpec getChatbots() {
        return chatbots;
    }

    public void setChatbots(ChatbotsSpec chatbots) {
        this.chatbots = chatbots;
    }

    public PagesSpec getPages() {
        return pages;
    }

    public void setPages(PagesSpec pages) {
        this.pages = pages;
    }

    public DeleteDefaultViewsSpec getDeleteDefaultViews() {
        return deleteDefaultViews;
    }

    public void setDeleteDefaultViews(DeleteDefaultViewsSpec deleteDefaultViews) {
        this.deleteDefaultViews = deleteDefaultViews;
    }

    public ExecutionSpec getExecution() {
        return execution;
    }

    public void setExecution(ExecutionSpec execution) {
        this.execution = execution;
    }

    /**
     * 验证Spec合法性
     */
    @JsonIgnore
    public void validate() {
        if (!"workflow_requirement_v1".equals(schemaVersion)) {
            throw new IllegalArgumentException("schema_version must be workflow_requirement_v1");
        }
        if (app == null || app.getName() == null || app.getName().isBlank()) {
            throw new IllegalArgumentException("app.name is required");
        }
    }

    @Override
    public String toString() {
        return "RequirementSpec{" +
            "schemaVersion='" + schemaVersion + '\'' +
            ", app=" + app.getName() +
            ", language=" + (meta != null ? meta.getLanguage() : "zh") +
            '}';
    }
}
