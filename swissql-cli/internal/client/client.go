package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
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

func (c *Client) Status() error {
	url := fmt.Sprintf("%s/v1/status", c.BaseURL)
	resp, err := c.getWithTimeout(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("API error: status=%d", resp.StatusCode)
	}
	return nil
}

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

type ConnectionProfileResponse struct {
	ProfileId            string            `json:"profile_id"`
	Name                 string            `json:"name"`
	DbType               string            `json:"db_type"`
	DsnMasked            string            `json:"dsn_masked"`
	Username             string            `json:"username"`
	CredentialConfigured bool              `json:"credential_configured"`
	CredentialSource     string            `json:"credential_source"`
	Enabled              bool              `json:"enabled"`
	Labels               map[string]string `json:"labels,omitempty"`
}

type ConnectionCreateRequest struct {
	ProfileId    string            `json:"profile_id,omitempty"`
	Name         string            `json:"name"`
	DbType       string            `json:"db_type"`
	Dsn          string            `json:"dsn"`
	Username     string            `json:"username,omitempty"`
	Password     string            `json:"password,omitempty"`
	SavePassword *bool             `json:"save_password,omitempty"`
	Labels       map[string]string `json:"labels,omitempty"`
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

func (c *Client) SqlExecute(req *SqlExecuteRequest) (*ExecuteResponse, error) {
	url := fmt.Sprintf("%s/v1/sql/execute", c.BaseURL)
	respBody, err := c.post(url, req)
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

func (c *Client) ConnectionsList() (*ConnectionsListResponse, error) {
	urlStr := fmt.Sprintf("%s/v1/connections", c.BaseURL)
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

func (c *Client) post(url string, body interface{}) (io.ReadCloser, error) {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequest("POST", url, bytes.NewBuffer(jsonBody))
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


