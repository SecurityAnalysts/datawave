package datawave.microservice.query;

import datawave.marking.ColumnVisibilitySecurityMarking;
import datawave.microservice.audit.AuditClient;
import datawave.microservice.authorization.jwt.JWTRestTemplate;
import datawave.microservice.authorization.service.RemoteAuthorizationServiceUserDetailsService;
import datawave.microservice.authorization.user.ProxiedUserDetails;
import datawave.microservice.query.config.QueryProperties;
import datawave.microservice.query.storage.QueryStatus;
import datawave.microservice.query.storage.QueryStorageCache;
import datawave.microservice.query.storage.TaskKey;
import datawave.microservice.query.storage.TaskStates;
import datawave.security.authorization.DatawaveUser;
import datawave.security.authorization.SubjectIssuerDNPair;
import datawave.webservice.query.Query;
import datawave.webservice.query.exception.QueryExceptionType;
import datawave.webservice.result.GenericResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static datawave.microservice.query.QueryParameters.QUERY_MAX_CONCURRENT_TASKS;
import static datawave.microservice.query.QueryParameters.QUERY_MAX_RESULTS_OVERRIDE;
import static datawave.microservice.query.QueryParameters.QUERY_PAGESIZE;
import static datawave.security.authorization.DatawaveUser.UserType.USER;
import static datawave.webservice.common.audit.AuditParameters.AUDIT_ID;
import static datawave.webservice.common.audit.AuditParameters.QUERY_STRING;
import static datawave.webservice.query.QueryImpl.BEGIN_DATE;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"QueryServiceTest", RemoteAuthorizationServiceUserDetailsService.ACTIVATION_PROFILE})
public class QueryServiceTest {
    private static final String EXPECTED_AUDIT_URI = "http://localhost:11111/audit/v1/audit";
    private static final String TEST_QUERY_STRING = "FIELD:SOME_VALUE";
    private static final String TEST_QUERY_NAME = "The Greatest Query in the World - Tribute";
    private static final String TEST_QUERY_AUTHORIZATIONS = "ALL";
    private static final String TEST_QUERY_BEGIN = "20000101 000000.000";
    private static final String TEST_QUERY_END = "20500101 000000.000";
    private static final String TEST_VISIBILITY_MARKING = "ALL";
    
    @LocalServerPort
    private int webServicePort;
    
    @Autowired
    private RestTemplateBuilder restTemplateBuilder;
    
    private JWTRestTemplate jwtRestTemplate;
    
    private SubjectIssuerDNPair DN;
    private String userDN = "userDn";
    
    @Autowired
    private QueryStorageCache queryStorageCache;
    
    @Autowired
    private AuditClient auditClient;
    
    @Autowired
    private QueryProperties queryProperties;
    
    private List<String> auditIds;
    private MockRestServiceServer mockServer;
    
    @Before
    public void setup() {
        auditIds = new ArrayList<>();
        
        jwtRestTemplate = restTemplateBuilder.build(JWTRestTemplate.class);
        jwtRestTemplate.setErrorHandler(new NoOpResponseErrorHandler());
        DN = SubjectIssuerDNPair.of(userDN, "issuerDn");
        
        RestTemplate auditorRestTemplate = (RestTemplate) new DirectFieldAccessor(auditClient).getPropertyValue("jwtRestTemplate");
        mockServer = MockRestServiceServer.createServer(auditorRestTemplate);
    }
    
    @DirtiesContext
    @Test
    public void testDefineSuccess() throws ParseException, IOException {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        long currentTimeMillis = System.currentTimeMillis();
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.SUCCESSFUL,
                resp);
        // @formatter:on
        
        // verify that a query id was returned
        String queryId = genericResponse.getResult();
        Assert.assertNotNull(queryId);
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.DEFINED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that they query was created correctly
        Query query = queryStatus.getQuery();
        // @formatter:off
        assertQuery(
                TEST_QUERY_STRING,
                TEST_QUERY_NAME,
                TEST_QUERY_AUTHORIZATIONS,
                TEST_QUERY_BEGIN,
                TEST_QUERY_END,
                TEST_VISIBILITY_MARKING,
                query);
        // @formatter:on
        
        // verify that no audit message was sent
        assertAuditNotSent();
        
        // verify that query tasks weren't created
        assertTasksNotCreated(queryId);
    }
    
    @Test
    public void testDefineFailure_paramValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // remove the query param to induce a parameter validation failure
        map.remove(QUERY_STRING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Missing one or more required QueryParameters",
                "java.lang.IllegalArgumentException: Missing one or more required QueryParameters",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_authValidation() {
        ProxiedUserDetails authUser = createUserDetails(Collections.singleton("AuthorizedUser"), Collections.emptyList());
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "java.lang.IllegalArgumentException: User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_queryLogicValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // remove the beginDate param to induce a query logic validation failure
        map.remove(BEGIN_DATE);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter begin not found",
                "java.lang.IllegalArgumentException: Required parameter begin not found",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_maxPageSize() {
        ProxiedUserDetails authUser = createUserDetails(Arrays.asList("AuthorizedUser", queryProperties.getPrivilegedRole()), null);
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid page size override
        map.set(QUERY_PAGESIZE, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Page size is larger than configured max. Max = 10,000.",
                "Exception with no cause caught",
                "400-6",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_maxResultsOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid max results override
        map.set(QUERY_MAX_RESULTS_OVERRIDE, Long.toString(Long.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max results override value. Max = 1,000,000.",
                "Exception with no cause caught",
                "400-43",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_maxConcurrentTasksOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // add an invalid max results override
        map.set(QUERY_MAX_CONCURRENT_TASKS, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max concurrent tasks override value. Max = 10.",
                "Exception with no cause caught",
                "400-44",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_roleValidation() {
        // create a user without the required role
        ProxiedUserDetails authUser = createUserDetails(Collections.emptyList(), Collections.singletonList("ALL"));
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User does not have the required roles.",
                "datawave.webservice.query.exception.UnauthorizedQueryException: User does not have the required roles.",
                "400-5",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @Test
    public void testDefineFailure_markingValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/define");
        MultiValueMap<String,String> map = createParams();
        
        // remove the column visibility param to induce a security marking validation failure
        map.remove(ColumnVisibilitySecurityMarking.VISIBILITY_MARKING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter columnVisibility not found",
                "java.lang.IllegalArgumentException: Required parameter columnVisibility not found",
                "400-4",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
    }
    
    @DirtiesContext
    @Test
    public void testCreateSuccess() throws ParseException, IOException {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditSentSetup();
        
        long currentTimeMillis = System.currentTimeMillis();
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                true,
                HttpStatus.Series.SUCCESSFUL,
                resp);
        // @formatter:on
        
        // verify that a query id was returned
        String queryId = genericResponse.getResult();
        Assert.assertNotNull(queryId);
        
        // verify that query status was created correctly
        QueryStatus queryStatus = queryStorageCache.getQueryStatus(queryId);
        
        // @formatter:off
        assertQueryStatus(
                QueryStatus.QUERY_STATE.CREATED,
                0,
                0,
                0,
                0,
                currentTimeMillis,
                queryStatus);
        // @formatter:on
        
        // verify that they query was created correctly
        Query query = queryStatus.getQuery();
        
        // @formatter:off
        assertQuery(
                TEST_QUERY_STRING,
                TEST_QUERY_NAME,
                TEST_QUERY_AUTHORIZATIONS,
                TEST_QUERY_BEGIN,
                TEST_QUERY_END,
                TEST_VISIBILITY_MARKING,
                query);
        // @formatter:on
        
        // verify that an audit message was sent and the the audit id matches the query id
        assertAuditSent(queryId);
        
        // verify that query tasks were created
        assertTasksCreated(queryId);
        
        // TODO: Things to test
        // successful query definition
        // failed parameter validation
        // query logic parameter validation
        // missing required roles
        // failed security marking validation
        // failed query storage
        // unknown error
    }
    
    @Test
    public void testCreateFailure_paramValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // remove the query param to induce a parameter validation failure
        map.remove(QUERY_STRING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Missing one or more required QueryParameters",
                "java.lang.IllegalArgumentException: Missing one or more required QueryParameters",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_authValidation() {
        ProxiedUserDetails authUser = createUserDetails(Collections.singleton("AuthorizedUser"), Collections.emptyList());
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "java.lang.IllegalArgumentException: User requested authorizations that they don't have. Missing: [ALL], Requested: [ALL], User: []",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_queryLogicValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // remove the beginDate param to induce a query logic validation failure
        map.remove(BEGIN_DATE);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter begin not found",
                "java.lang.IllegalArgumentException: Required parameter begin not found",
                "400-1",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_maxPageSize() {
        ProxiedUserDetails authUser = createUserDetails(Arrays.asList("AuthorizedUser", queryProperties.getPrivilegedRole()), null);
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid page size override
        map.set(QUERY_PAGESIZE, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Page size is larger than configured max. Max = 10,000.",
                "Exception with no cause caught",
                "400-6",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_maxResultsOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // set an invalid max results override
        map.set(QUERY_MAX_RESULTS_OVERRIDE, Long.toString(Long.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max results override value. Max = 1,000,000.",
                "Exception with no cause caught",
                "400-43",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_maxConcurrentTasksOverride() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // add an invalid max results override
        map.set(QUERY_MAX_CONCURRENT_TASKS, Integer.toString(Integer.MAX_VALUE));
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Invalid max concurrent tasks override value. Max = 10.",
                "Exception with no cause caught",
                "400-44",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_roleValidation() {
        // create a user without the required role
        ProxiedUserDetails authUser = createUserDetails(Collections.emptyList(), Collections.singletonList("ALL"));
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "User does not have the required roles.",
                "datawave.webservice.query.exception.UnauthorizedQueryException: User does not have the required roles.",
                "400-5",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    @Test
    public void testCreateFailure_markingValidation() {
        ProxiedUserDetails authUser = createUserDetails();
        UriComponents uri = createUri("EventQuery/create");
        MultiValueMap<String,String> map = createParams();
        
        // remove the column visibility param to induce a security marking validation failure
        map.remove(ColumnVisibilitySecurityMarking.VISIBILITY_MARKING);
        
        RequestEntity<MultiValueMap<String,String>> requestEntity = jwtRestTemplate.createRequestEntity(authUser, map, null, HttpMethod.POST, uri);
        
        // setup a mock audit service
        auditNotSentSetup();
        
        ResponseEntity<GenericResponse> resp = jwtRestTemplate.exchange(requestEntity, GenericResponse.class);
        
        // @formatter:off
        GenericResponse<String> genericResponse = assertGenericResponse(
                false,
                HttpStatus.Series.CLIENT_ERROR,
                resp);
        // @formatter:on
        
        // verify that there is no result
        Assert.assertNull(genericResponse.getResult());
        
        // verify that an exception was returned
        Assert.assertEquals(1, genericResponse.getExceptions().size());
        
        QueryExceptionType queryException = genericResponse.getExceptions().get(0);
        // @formatter:off
        assertQueryException(
                "Required parameter columnVisibility not found",
                "java.lang.IllegalArgumentException: Required parameter columnVisibility not found",
                "400-4",
                queryException);
        // @formatter:on
        
        // verify that there are no query statuses
        Assert.assertTrue(queryStorageCache.getQueryStatus().isEmpty());
        
        // verify that no audit message was sent
        assertAuditNotSent();
    }
    
    private ProxiedUserDetails createUserDetails() {
        return createUserDetails(null, null);
    }
    
    private ProxiedUserDetails createUserDetails(Collection<String> roles, Collection<String> auths) {
        Collection<String> userRoles = roles != null ? roles : Collections.singleton("AuthorizedUser");
        Collection<String> userAuths = auths != null ? auths : Collections.singleton("ALL");
        DatawaveUser datawaveUser = new DatawaveUser(DN, USER, userAuths, userRoles, null, System.currentTimeMillis());
        return new ProxiedUserDetails(Collections.singleton(datawaveUser), datawaveUser.getCreationTime());
    }
    
    private UriComponents createUri(String path) {
        return UriComponentsBuilder.newInstance().scheme("https").host("localhost").port(webServicePort).path("/query/v1/" + path).build();
    }
    
    private MultiValueMap<String,String> createParams() {
        MultiValueMap<String,String> map = new LinkedMultiValueMap<>();
        map.set(DefaultQueryParameters.QUERY_STRING, TEST_QUERY_STRING);
        map.set(DefaultQueryParameters.QUERY_NAME, TEST_QUERY_NAME);
        map.set(DefaultQueryParameters.QUERY_AUTHORIZATIONS, TEST_QUERY_AUTHORIZATIONS);
        map.set(DefaultQueryParameters.QUERY_BEGIN, TEST_QUERY_BEGIN);
        map.set(DefaultQueryParameters.QUERY_END, TEST_QUERY_END);
        map.set(ColumnVisibilitySecurityMarking.VISIBILITY_MARKING, TEST_VISIBILITY_MARKING);
        map.set(QUERY_MAX_CONCURRENT_TASKS, Integer.toString(1));
        map.set(QUERY_MAX_RESULTS_OVERRIDE, Long.toString(1234));
        map.set(QUERY_PAGESIZE, Long.toString(123));
        return map;
    }
    
    private void assertQueryStatus(QueryStatus.QUERY_STATE queryState, long numResultsReturned, long numResultsGenerated, long concurrentNextCount,
                    long lastPageNumber, long lastCallTimeMillis, QueryStatus queryStatus) {
        Assert.assertEquals(queryState, queryStatus.getQueryState());
        Assert.assertEquals(numResultsReturned, queryStatus.getNumResultsReturned());
        Assert.assertEquals(numResultsGenerated, queryStatus.getNumResultsGenerated());
        Assert.assertEquals(concurrentNextCount, queryStatus.getConcurrentNextCount());
        Assert.assertEquals(lastPageNumber, queryStatus.getLastPageNumber());
        Assert.assertTrue(queryStatus.getLastUsedMillis() > lastCallTimeMillis);
        Assert.assertTrue(queryStatus.getLastUpdatedMillis() > lastCallTimeMillis);
    }
    
    private void assertQuery(String queryString, String queryName, String authorizations, String begin, String end, String visibility, Query query)
                    throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat(DefaultQueryParameters.formatPattern);
        Assert.assertEquals(queryString, query.getQuery());
        Assert.assertEquals(queryName, query.getQueryName());
        Assert.assertEquals(authorizations, query.getQueryAuthorizations());
        Assert.assertEquals(sdf.parse(begin), query.getBeginDate());
        Assert.assertEquals(sdf.parse(end), query.getEndDate());
        Assert.assertEquals(visibility, query.getColumnVisibility());
    }
    
    private void assertTasksCreated(String queryId) throws IOException {
        // verify that the query task states were created
        TaskStates taskStates = queryStorageCache.getTaskStates(queryId);
        Assert.assertNotNull(taskStates);
        
        // verify that a query task was created
        List<TaskKey> taskKeys = queryStorageCache.getTasks(queryId);
        Assert.assertFalse(taskKeys.isEmpty());
    }
    
    private void assertTasksNotCreated(String queryId) throws IOException {
        // verify that the query task states were not created
        TaskStates taskStates = queryStorageCache.getTaskStates(queryId);
        Assert.assertNull(taskStates);
        
        // verify that a query task was not created
        List<TaskKey> taskKeys = queryStorageCache.getTasks(queryId);
        Assert.assertTrue(taskKeys.isEmpty());
    }
    
    public RequestMatcher auditIdGrabber() {
        return request -> {
            List<NameValuePair> params = URLEncodedUtils.parse(request.getBody().toString(), Charset.defaultCharset());
            params.stream().filter(p -> p.getName().equals(AUDIT_ID)).forEach(p -> auditIds.add(p.getValue()));
        };
    }
    
    private void auditSentSetup() {
        mockServer.expect(requestTo(EXPECTED_AUDIT_URI)).andExpect(auditIdGrabber()).andRespond(withSuccess());
    }
    
    private void auditNotSentSetup() {
        mockServer.expect(never(), requestTo(EXPECTED_AUDIT_URI)).andExpect(auditIdGrabber()).andRespond(withSuccess());
    }
    
    private void assertAuditSent(String queryId) {
        mockServer.verify();
        Assert.assertEquals(1, auditIds.size());
        Assert.assertEquals(queryId, auditIds.get(0));
    }
    
    private void assertAuditNotSent() {
        mockServer.verify();
        Assert.assertEquals(0, auditIds.size());
    }
    
    private void assertQueryException(String message, String cause, String code, QueryExceptionType queryException) {
        Assert.assertEquals(message, queryException.getMessage());
        Assert.assertEquals(cause, queryException.getCause());
        Assert.assertEquals(code, queryException.getCode());
    }
    
    @SuppressWarnings("unchecked")
    private GenericResponse<String> assertGenericResponse(boolean hasResults, HttpStatus.Series series, ResponseEntity<GenericResponse> response) {
        Assert.assertEquals(series, response.getStatusCode().series());
        Assert.assertNotNull(response);
        GenericResponse<String> genericResponse = (GenericResponse<String>) response.getBody();
        Assert.assertNotNull(genericResponse);
        Assert.assertEquals(hasResults, genericResponse.getHasResults());
        return genericResponse;
    }
    
    private static class NoOpResponseErrorHandler extends DefaultResponseErrorHandler {
        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            // do nothing
        }
    }
    
    @Configuration
    @Profile("QueryServiceTest")
    @ComponentScan(basePackages = "datawave.microservice")
    public static class QueryServiceTestConfiguration {
        
    }
}