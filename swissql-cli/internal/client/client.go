package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

type Client struct {
	BaseURL    string
	HTTPClient *http.Client
	Timeout    time.Duration
}

func NewClient(baseURL string, timeout time.Duration) *Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.DialContext = (&net.Dialer{Timeout: timeout}).DialContext
	transport.TLSHandshakeTimeout = timeout
	// NOTE: Do not set ResponseHeaderTimeout here.
	// Long-running SQL requests may legitimately take a long time before the server sends headers.
	// We only want client-side timeout to apply to connecting to the backend (dial/TLS).

	return &Client{
		BaseURL: baseURL,
		HTTPClient: &http.Client{
			Transport: transport,
		},
		Timeout: timeout,
	}
}

// ---------------------------------------------------------------------------
// Request / Response types
// ---------------------------------------------------------------------------

type SqlExecuteRequest struct {
	ProfileId  string            `json:"profile_id"`
	Sql        string            `json:"sql"`
	AllowWrite bool              `json:"allow_write"`
	Options    SqlExecuteOptions `json:"options"`
}

type SqlExecuteOptions struct {
	Limit     int `json:"limit,omitempty"`
	FetchSize int `json:"fetch_size,omitempty"`
	TimeoutMs int `json:"timeout_ms,omitempty"`
}

type ExecuteResponse struct {
	Type     string           `json:"type"`
	Schema   string           `json:"schema"`
	Data     DataContent      `json:"data"`
	Metadata ResponseMetadata `json:"metadata"`
}

type DriversResponse struct {
	Drivers []DriverEntry `json:"drivers"`
}

type DriversReloadResponse struct {
	Status   string                 `json:"status"`
	Reloaded map[string]interface{} `json:"reloaded"`
}

type DriverEntry struct {
	DbType          string   `json:"db_type"`
	Source          string   `json:"source"`
	DriverClass     string   `json:"driver_class"`
	DriverClasses   []string `json:"driver_classes"`
	JarPaths        []string `json:"jar_paths"`
	JdbcUrlTemplate string   `json:"jdbc_url_template"`
	DefaultPort     *int     `json:"default_port"`
}

type ConnectionsListResponse struct {
	Connections []ConnectionProfileResponse `json:"connections"`
	TraceId     string                      `json:"trace_id"`
}

type ProfileSource struct {
	Kind         string `json:"kind"`
	Provider     string `json:"provider,omitempty"`
	Driver       string `json:"driver,omitempty"`
	ConnectionId string `json:"connection_id,omitempty"`
}

type ConnectionProfileResponse struct {
	ProfileId            string            `json:"profile_id"`
	Name                 string            `json:"name"`
	DbType               string            `json:"db_type"`
	DsnMasked            string            `json:"dsn_masked"`
	Username             string            `json:"username"`
	CredentialConfigured bool              `json:"credential_configured"`
	CredentialSource     string            `json:"credential_source"`
	Enabled              bool              `json:"enabled"`
	Source               *ProfileSource    `json:"source,omitempty"`
	Labels               map[string]string `json:"labels,omitempty"`
	CreatedAt            *time.Time        `json:"created_at,omitempty"`
	UpdatedAt            *time.Time        `json:"updated_at,omitempty"`
}

type ConnectionCreateRequest struct {
	ProfileId     string            `json:"profile_id,omitempty"`
	Name          string            `json:"name"`
	DbType        string            `json:"db_type"`
	Dsn           string            `json:"dsn"`
	Username      string            `json:"username,omitempty"`
	Password      string            `json:"password,omitempty"`
	SavePassword  *bool             `json:"save_password,omitempty"`
	CredentialRef string            `json:"credential_ref,omitempty"`
	Enabled       *bool             `json:"enabled,omitempty"`
	Labels        map[string]string `json:"labels,omitempty"`
}

// ConnectionUpdateRequest uses pointer fields so that only explicitly-set
// fields are included in the PATCH body (partial-update semantics).
// Labels uses a pointer so nil means "do not change" while an empty map clears all labels.
type ConnectionUpdateRequest struct {
	Name          *string            `json:"name,omitempty"`
	DbType        *string            `json:"db_type,omitempty"`
	Dsn           *string            `json:"dsn,omitempty"`
	Username      *string            `json:"username,omitempty"`
	Password      *string            `json:"password,omitempty"`
	SavePassword  *bool              `json:"save_password,omitempty"`
	CredentialRef *string            `json:"credential_ref,omitempty"`
	Enabled       *bool              `json:"enabled,omitempty"`
	Labels        *map[string]string `json:"labels,omitempty"`
}

type ConnectionTestResponse struct {
	Status     string `json:"status"`
	Ok         bool   `json:"ok"`
	ProfileId  string `json:"profile_id"`
	DbType     string `json:"db_type"`
	DurationMs int64  `json:"duration_ms"`
	Message    string `json:"message"`
	TraceId    string `json:"trace_id"`
}

type ConnectionTestDraftRequest struct {
	DbType        string `json:"db_type,omitempty"`
	Dsn           string `json:"dsn,omitempty"`
	Username      string `json:"username,omitempty"`
	Password      string `json:"password,omitempty"`
	CredentialRef string `json:"credential_ref,omitempty"`
	TimeoutMs     *int   `json:"timeout_ms,omitempty"`
}

type DbeaverImportResponse struct {
	Discovered  int                         `json:"discovered"`
	Created     int                         `json:"created"`
	Skipped     int                         `json:"skipped"`
	Overwritten int                         `json:"overwritten"`
	Errors      []DbeaverImportError        `json:"errors"`
	Profiles    []ConnectionProfileResponse `json:"profiles"`
	TraceId     string                      `json:"trace_id"`
}

type DbeaverImportError struct {
	ConnectionName string `json:"connection_name"`
	Message        string `json:"message"`
}

type StatusResponse struct {
	Status     string `json:"status"`
	AppVersion string `json:"app_version,omitempty"`
}

type CapabilitiesResponse struct {
	Version          string   `json:"version"`
	Features         []string `json:"features"`
	SupportedDbTypes []string `json:"supported_db_types"`
	Endpoints        []string `json:"endpoints"`
	TraceId          string   `json:"trace_id"`
}

// ---------------------------------------------------------------------------
// Rules types
// ---------------------------------------------------------------------------

// RuleInfo represents a single SQL rule entry as returned by GET /v1/sql/rules.
type RuleInfo struct {
	ID          string      `json:"id"`
	Description string      `json:"description"`
	Scope       interface{} `json:"scope"`
	Match       interface{} `json:"match"`
}

// RulesListResponse is the response for GET /v1/sql/rules.
type RulesListResponse struct {
	Version       string     `json:"version"`
	DefaultAction string     `json:"default_action"`
	DefaultRuleID string     `json:"default_rule_id"`
	DenyRules     []RuleInfo `json:"deny_rules"`
	AllowRules    []RuleInfo `json:"allow_rules"`
	Source        string     `json:"source"`
	LoadedAt      string     `json:"loaded_at"`
	Mode          string     `json:"mode"`
}

// RulesReloadResponse is the response for POST /v1/sql/rules/reload.
type RulesReloadResponse struct {
	Reloaded   bool   `json:"reloaded"`
	Source     string `json:"source"`
	DenyCount  int    `json:"deny_count"`
	AllowCount int    `json:"allow_count"`
}

// RulesValidateRequest is the request body for POST /v1/sql/rules/validate.
type RulesValidateRequest struct {
	SQL        string `json:"sql"`
	ProfileID  string `json:"profile_id,omitempty"`
	AllowWrite bool   `json:"allow_write,omitempty"`
}

// RulesValidateResponse is the response for POST /v1/sql/rules/validate.
type RulesValidateResponse struct {
	Allowed                   bool              `json:"allowed"`
	Action                    string            `json:"action"`
	MatchedRuleID             string            `json:"matched_rule_id"`
	MatchedRuleDescription    string            `json:"matched_rule_description"`
	DefaultActionUsed         bool              `json:"default_action_used"`
	WriteLike                 bool              `json:"write_like"`
	RequestAllowWriteRequired bool              `json:"request_allow_write_required"`
	ProfileID                 string            `json:"profile_id"`
	Labels                    map[string]string `json:"labels"`
}

type RulesInitResponse struct {
	Path     string `json:"path"`
	Mode     string `json:"mode"`
	Reloaded bool   `json:"reloaded"`
}

// ConnectionsListFilter holds optional server-side filter parameters for ConnectionsListFiltered.
type ConnectionsListFilter struct {
	DbType       string
	Enabled      *bool
	NameContains string
	Labels       []string
}

func (r *DriversResponse) HasDbType(dbType string) bool {
	if r == nil {
		return false
	}
	needle := strings.ToLower(strings.TrimSpace(dbType))
	if needle == "" {
		return false
	}
	for _, d := range r.Drivers {
		if strings.ToLower(strings.TrimSpace(d.DbType)) == needle {
			return true
		}
	}
	return false
}

type DataContent struct {
	TextContent string                   `json:"text_content,omitempty"`
	Columns     []ColumnDefinition       `json:"columns,omitempty"`
	Rows        []map[string]interface{} `json:"rows,omitempty"`
}

type ColumnDefinition struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

type ResponseMetadata struct {
	DurationMs   int  `json:"duration_ms"`
	RowsAffected int  `json:"rows_affected"`
	Truncated    bool `json:"truncated"`
}

type ErrorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	TraceId string `json:"trace_id"`
}

type ApiErrorKind string

const (
	ApiErrorKindAPI ApiErrorKind = "api"
	ApiErrorKindDB  ApiErrorKind = "db"
)

type ApiError struct {
	Kind    ApiErrorKind
	Status  int
	Code    string
	Message string
	TraceId string
}

func (e *ApiError) Error() string {
	if e == nil {
		return ""
	}

	prefix := "API error"
	if e.Kind == ApiErrorKindDB {
		prefix = "DB error"
	}

	if e.Code != "" && e.TraceId != "" {
		return fmt.Sprintf("%s: [%s] %s (trace_id: %s)", prefix, e.Code, e.Message, e.TraceId)
	}
	if e.Code != "" {
		return fmt.Sprintf("%s: [%s] %s", prefix, e.Code, e.Message)
	}
	return fmt.Sprintf("%s: %s", prefix, e.Message)
}

var leadingErrorPrefixRegex = regexp.MustCompile(`(?i)^(error:\s*)+`)

func sanitizeDbErrorMessage(msg string) string {
	s := strings.TrimSpace(msg)
	s = leadingErrorPrefixRegex.ReplaceAllString(s, "")
	return strings.TrimSpace(s)
}

// ---------------------------------------------------------------------------
// API methods
// ---------------------------------------------------------------------------

func (c *Client) SqlExecute(req *SqlExecuteRequest) (*ExecuteResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/sql/execute", c.BaseURL)
	respBody, err := c.post(urlStr, req)
	if err != nil {
		return nil, err
	}
	defer respBody.Close()

	var resp ExecuteResponse
	if err := json.NewDecoder(respBody).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ConnectionsList returns all profiles with no filtering (convenience wrapper).
func (c *Client) ConnectionsList() (*ConnectionsListResponse, error) {
	return c.ConnectionsListFiltered(ConnectionsListFilter{})
}

// ConnectionsListFiltered returns profiles filtered by the given criteria.
// All filter fields are optional; omitting a field means no filtering on that dimension.
func (c *Client) ConnectionsListFiltered(filter ConnectionsListFilter) (*ConnectionsListResponse, error) {
	base := fmt.Sprintf("%s/v1/connections", c.BaseURL)
	params := url.Values{}
	if filter.DbType != "" {
		params.Set("db_type", filter.DbType)
	}
	if filter.Enabled != nil {
		if *filter.Enabled {
			params.Set("enabled", "true")
		} else {
			params.Set("enabled", "false")
		}
	}
	if filter.NameContains != "" {
		params.Set("name_contains", filter.NameContains)
	}
	for _, label := range filter.Labels {
		params.Add("label", label)
	}
	urlStr := base
	if encoded := params.Encode(); encoded != "" {
		urlStr = base + "?" + encoded
	}
	body, err := c.get(urlStr)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp ConnectionsListResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ConnectionGet fetches a single profile by ID.
func (c *Client) ConnectionGet(profileId string) (*ConnectionProfileResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections/%s", c.BaseURL, url.PathEscape(profileId))
	body, err := c.get(urlStr)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp ConnectionProfileResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

func (c *Client) ConnectionAdd(req *ConnectionCreateRequest) (*ConnectionProfileResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections", c.BaseURL)
	body, err := c.post(urlStr, req)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp ConnectionProfileResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ConnectionUpdate sends a partial PATCH update. Only non-nil fields in req are sent.
func (c *Client) ConnectionUpdate(profileId string, req *ConnectionUpdateRequest) (*ConnectionProfileResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections/%s", c.BaseURL, url.PathEscape(profileId))
	body, err := c.patch(urlStr, req)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp ConnectionProfileResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ConnectionDelete deletes a profile by ID.
func (c *Client) ConnectionDelete(profileId string) error {
	urlStr := fmt.Sprintf("%s/v1/connections/%s", c.BaseURL, url.PathEscape(profileId))
	body, err := c.delete(urlStr)
	if err != nil {
		return err
	}
	if body != nil {
		body.Close()
	}
	return nil
}

func (c *Client) ConnectionTest(profileId string) (*ConnectionTestResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections/%s/test", c.BaseURL, url.PathEscape(profileId))
	body, err := c.post(urlStr, map[string]interface{}{})
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp ConnectionTestResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ConnectionTestDraft tests a draft connection without creating a profile.
func (c *Client) ConnectionTestDraft(req *ConnectionTestDraftRequest) (*ConnectionTestResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections/test", c.BaseURL)
	body, err := c.post(urlStr, req)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp ConnectionTestResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ConnectionImportDbeaver imports profiles from a DBeaver .dbp archive via multipart upload.
func (c *Client) ConnectionImportDbeaver(filePath string, dryRun bool, onConflict string, namePrefix string) (*DbeaverImportResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections/import/dbeaver", c.BaseURL)
	body, err := c.postMultipart(urlStr, filePath, dryRun, onConflict, namePrefix)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp DbeaverImportResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

func (c *Client) CoreDrivers() (*DriversResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/drivers", c.BaseURL)
	body, err := c.get(urlStr)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp DriversResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

func (c *Client) CoreReloadDrivers() (*DriversReloadResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/drivers/reload", c.BaseURL)
	body, err := c.post(urlStr, map[string]interface{}{})
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp DriversReloadResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// GetStatus returns the backend health status as a structured response.
func (c *Client) GetStatus() (*StatusResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/status", c.BaseURL)
	body, err := c.get(urlStr)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp StatusResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// GetCapabilities returns the backend capabilities (loaded drivers, feature flags).
func (c *Client) GetCapabilities() (*CapabilitiesResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/capabilities", c.BaseURL)
	body, err := c.get(urlStr)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp CapabilitiesResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// RulesExamples downloads an example SQL rules YAML from the backend (GET /v1/sql/rules/examples?mode=...).
// The caller is responsible for closing the returned ReadCloser.
func (c *Client) RulesExamples(mode string) (io.ReadCloser, error) {
	params := url.Values{}
	params.Set("mode", mode)
	urlStr := fmt.Sprintf("%s/v1/sql/rules/examples?%s", c.BaseURL, params.Encode())
	return c.get(urlStr)
}

// RulesInit initializes sql-rules.yaml on the backend machine (POST /v1/sql/rules/init?mode=...&force=...).
func (c *Client) RulesInit(mode string, force bool) (*RulesInitResponse, error) {
	params := url.Values{}
	params.Set("mode", mode)
	params.Set("force", fmt.Sprintf("%t", force))
	urlStr := fmt.Sprintf("%s/v1/sql/rules/init?%s", c.BaseURL, params.Encode())
	body, err := c.post(urlStr, nil)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp RulesInitResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, fmt.Errorf("failed to decode response: %w", err)
	}
	return &resp, nil
}

// RulesList fetches the active SQL rule set from the backend (GET /v1/sql/rules).
func (c *Client) RulesList() (*RulesListResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/sql/rules", c.BaseURL)
	body, err := c.get(urlStr)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp RulesListResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// RulesReload triggers a hot reload of the SQL rule set (POST /v1/sql/rules/reload).
func (c *Client) RulesReload() (*RulesReloadResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/sql/rules/reload", c.BaseURL)
	body, err := c.post(urlStr, map[string]interface{}{})
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp RulesReloadResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// RulesValidate validates a SQL statement against the active rule set (POST /v1/sql/rules/validate).
func (c *Client) RulesValidate(req *RulesValidateRequest) (*RulesValidateResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/sql/rules/validate", c.BaseURL)
	body, err := c.post(urlStr, req)
	if err != nil {
		return nil, err
	}
	defer body.Close()

	var resp RulesValidateResponse
	if err := json.NewDecoder(body).Decode(&resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

func (c *Client) post(urlStr string, body interface{}) (io.ReadCloser, error) {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequest(http.MethodPost, urlStr, bytes.NewBuffer(jsonBody))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()

		kind := ApiErrorKindAPI
		msg := errResp.Message
		if errResp.Code == "EXECUTION_ERROR" {
			kind = ApiErrorKindDB
			msg = sanitizeDbErrorMessage(msg)
		}

		return nil, &ApiError{
			Kind:    kind,
			Status:  resp.StatusCode,
			Code:    errResp.Code,
			Message: msg,
			TraceId: errResp.TraceId,
		}
	}

	return resp.Body, nil
}

func (c *Client) patch(urlStr string, body interface{}) (io.ReadCloser, error) {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequest(http.MethodPatch, urlStr, bytes.NewBuffer(jsonBody))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()
		if errResp.Code != "" {
			kind := ApiErrorKindAPI
			msg := errResp.Message
			if errResp.Code == "EXECUTION_ERROR" {
				kind = ApiErrorKindDB
				msg = sanitizeDbErrorMessage(msg)
			}
			return nil, &ApiError{Kind: kind, Status: resp.StatusCode, Code: errResp.Code, Message: msg, TraceId: errResp.TraceId}
		}
		return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Message: fmt.Sprintf("status=%d", resp.StatusCode)}
	}

	return resp.Body, nil
}

func (c *Client) put(urlStr string, body interface{}) (io.ReadCloser, error) {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequest(http.MethodPut, urlStr, bytes.NewBuffer(jsonBody))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()
		if errResp.Code != "" {
			kind := ApiErrorKindAPI
			msg := errResp.Message
			if errResp.Code == "EXECUTION_ERROR" {
				kind = ApiErrorKindDB
				msg = sanitizeDbErrorMessage(msg)
			}
			return nil, &ApiError{Kind: kind, Status: resp.StatusCode, Code: errResp.Code, Message: msg, TraceId: errResp.TraceId}
		}
		return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Message: fmt.Sprintf("status=%d", resp.StatusCode)}
	}

	return resp.Body, nil
}

func (c *Client) delete(urlStr string) (io.ReadCloser, error) {
	req, err := http.NewRequest(http.MethodDelete, urlStr, nil)
	if err != nil {
		return nil, err
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()
		if errResp.Code != "" {
			kind := ApiErrorKindAPI
			msg := errResp.Message
			if errResp.Code == "EXECUTION_ERROR" {
				kind = ApiErrorKindDB
				msg = sanitizeDbErrorMessage(msg)
			}
			return nil, &ApiError{Kind: kind, Status: resp.StatusCode, Code: errResp.Code, Message: msg, TraceId: errResp.TraceId}
		}
		return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Message: fmt.Sprintf("status=%d", resp.StatusCode)}
	}

	return resp.Body, nil
}

// postMultipart uploads a file as multipart/form-data with additional form fields.
func (c *Client) postMultipart(urlStr string, filePath string, dryRun bool, onConflict string, namePrefix string) (io.ReadCloser, error) {
	f, err := os.Open(filePath)
	if err != nil {
		return nil, fmt.Errorf("failed to open file %s: %w", filePath, err)
	}
	defer f.Close()

	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)

	fw, err := mw.CreateFormFile("file", filepath.Base(filePath))
	if err != nil {
		return nil, err
	}
	if _, err := io.Copy(fw, f); err != nil {
		return nil, err
	}

	if dryRun {
		_ = mw.WriteField("dry_run", "true")
	}
	if onConflict != "" {
		_ = mw.WriteField("on_conflict", onConflict)
	}
	if namePrefix != "" {
		_ = mw.WriteField("name_prefix", namePrefix)
	}

	if err := mw.Close(); err != nil {
		return nil, err
	}

	req, err := http.NewRequest(http.MethodPost, urlStr, &buf)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()
		if errResp.Code != "" {
			return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Code: errResp.Code, Message: errResp.Message, TraceId: errResp.TraceId}
		}
		return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Message: fmt.Sprintf("status=%d", resp.StatusCode)}
	}

	return resp.Body, nil
}

func (c *Client) getWithTimeout(urlStr string) (*http.Response, error) {
	ctx, cancel := context.WithTimeout(context.Background(), c.Timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, urlStr, nil)
	if err != nil {
		return nil, err
	}

	return c.HTTPClient.Do(req)
}

func (c *Client) getProbe(urlStr string) (io.ReadCloser, error) {
	resp, err := c.getWithTimeout(urlStr)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()
		if errResp.Code != "" {
			kind := ApiErrorKindAPI
			msg := errResp.Message
			if errResp.Code == "EXECUTION_ERROR" {
				kind = ApiErrorKindDB
				msg = sanitizeDbErrorMessage(msg)
			}
			return nil, &ApiError{
				Kind:    kind,
				Status:  resp.StatusCode,
				Code:    errResp.Code,
				Message: msg,
				TraceId: errResp.TraceId,
			}
		}
		return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Message: fmt.Sprintf("status=%d", resp.StatusCode)}
	}

	return resp.Body, nil
}

func (c *Client) get(urlStr string) (io.ReadCloser, error) {
	req, err := http.NewRequest(http.MethodGet, urlStr, nil)
	if err != nil {
		return nil, err
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, err
	}

	if resp.StatusCode >= 400 {
		var errResp ErrorResponse
		json.NewDecoder(resp.Body).Decode(&errResp)
		resp.Body.Close()
		if errResp.Code != "" {
			kind := ApiErrorKindAPI
			msg := errResp.Message
			if errResp.Code == "EXECUTION_ERROR" {
				kind = ApiErrorKindDB
				msg = sanitizeDbErrorMessage(msg)
			}
			return nil, &ApiError{
				Kind:    kind,
				Status:  resp.StatusCode,
				Code:    errResp.Code,
				Message: msg,
				TraceId: errResp.TraceId,
			}
		}
		return nil, &ApiError{Kind: ApiErrorKindAPI, Status: resp.StatusCode, Message: fmt.Sprintf("status=%d", resp.StatusCode)}
	}

	return resp.Body, nil
}
