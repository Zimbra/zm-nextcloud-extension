/*

Copyright (C) 2016-2024  Barry de Graaff

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see http://www.gnu.org/licenses/.

*/

package com.zimbra.nextcloud;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.namespace.QName;

import com.zimbra.common.httpclient.HttpClientUtil;
import com.zimbra.common.util.ZimbraHttpConnectionManager;
import com.zimbra.cs.httpclient.HttpProxyUtil;
import com.zimbra.cs.servlet.util.AuthUtil;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import com.github.sardine.DavResource;
import com.github.sardine.impl.SardineImpl;
import com.github.sardine.impl.io.ContentLengthInputStream;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AuthToken;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Server;
import com.zimbra.cs.extension.ExtensionHttpHandler;
import com.zimbra.cs.httpclient.URLUtil;
import com.zimbra.oauth.token.handlers.impl.NextCloudTokenHandler;

public class Nextcloud extends ExtensionHttpHandler {
    public static final int request_timeout = 15000;

    /**
     * The path under which the handler is registered for an extension.
     *
     * @return path
     */
    @Override
    public String getPath() {
        return "/nextcloud";
    }

    /**
     * Processes HTTP GET requests.
     *
     * @param req  request message
     * @param resp response message
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        resp.getOutputStream().print("com.zimbra.nextcloud is installed.");
    }

    /**
     * Processes HTTP POST requests.
     *
     * @param req  request message
     * @param resp response message
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        Account account = null;
        String accessToken = null;
        Server server = null;

        //all authentication is done by AuthUtil.getAuthTokenFromHttpReq, returns null if unauthorized
        final AuthToken authToken = AuthUtil.getAuthTokenFromHttpReq(req, resp, false, true);
        if (authToken != null) {
            try {
                account = authToken.getAccount();
                accessToken = NextCloudTokenHandler.refreshAccessToken(account, "nextcloud");
                server = Provisioning.getInstance().getServer(account);
                ZimbraLog.extensions.info("Refresh token :" + accessToken + " " + server.getName());
            } catch (Exception e) {
                ZimbraLog.extensions.info("Error fetching refresh token ", e);
            }

            try {
                JSONObject receivedJSON = new JSONObject(IOUtils.toString(req.getPart("jsondata").getInputStream(), "UTF-8"));
                String action = receivedJSON.getString("nextcloudAction");
                String path = receivedJSON.getString("nextcloudPath");
                String nextcloudDAVPath = receivedJSON.getString("nextcloudDAVPath");

                SardineImpl sardine = new SardineImpl(accessToken);
                //having to do a replace for spaces, maybe a bug in Sardine.
                path = nextcloudDAVPath + uriEncode(path).replace("%2F", "/");

                switch (action) {
                    case "propfind":
                        JSONArray propfindResponse = this.propfind(sardine, path);
                        resp.setContentType("application/json");
                        resp.setCharacterEncoding("UTF-8");
                        resp.getOutputStream().print(propfindResponse.toString());
                        break;
                    case "get":
                        ContentLengthInputStream is = sardine.get(path);
                        Header[] originalHeadersFromNc = is.getResponse().getAllHeaders();
                        for (int n = 0; n < originalHeadersFromNc.length; n++) {
                            Header header = originalHeadersFromNc[n];
                            if ("Content-Type".equals(header.getName())) {
                                resp.setHeader("Content-Type", header.getValue());
                            }
                            if ("Content-Disposition".equals(header.getName())) {
                                resp.setHeader("Content-Disposition", header.getValue());
                            }
                        }
                        // can be used from Java 9 and up, since Zimbra is compiling at level 8 ATM this cannot be used.
                        //is.transferTo(resp.getOutputStream());
                        IOUtils.copy(is, resp.getOutputStream());
                        is.close();
                        break;
                    case "put":
                        String name = receivedJSON.getString("nextcloudFilename");
                        ZimbraLog.extensions.info("PUT action");
                        //having to do a replace for spaces, maybe a bug in Sardine.
                        name = uriEncode(name).replace("%2F", "/");
                        resp.setContentType("application/json");
                        resp.setCharacterEncoding("UTF-8");
                        resp.getOutputStream().print(receivedJSON.toString());
                        if (fetchMail(req, authToken, accessToken, path, name, receivedJSON, server)) {
                            resp.setStatus(200);
                        } else {
                            resp.setStatus(500);
                        }
                        break;
                    case "createShare":
                        String OCSPath = receivedJSON.getString("OCSPath");
                        String shareType = receivedJSON.getString("shareType");
                        String password = receivedJSON.getString("password");
                        String expiryDate = receivedJSON.getString("expiryDate");
                        path = uriEncode(receivedJSON.getString("nextcloudPath")).replace("%2F", "/");
                        resp.setContentType("application/json");
                        resp.setCharacterEncoding("UTF-8");
                        //status is set from within createShare method
                        resp.getOutputStream().print(createShare(accessToken, OCSPath, path, shareType, password, expiryDate, resp));
                        break;
                    case "createTalkConv":
                        JSONObject body = receivedJSON.getJSONObject("body");
                        resp.setContentType("application/json");
                        resp.setCharacterEncoding("UTF-8");
                        resp.getOutputStream().print(doPostRequestToNextcloud(accessToken, body, receivedJSON.getString("NextcloudApiURL")));
                        break;
                    default:
                        resp.getOutputStream().print("com.zimbra.nextcloud is installed.");
                        return;
                }

            } catch (
                    Exception e) {
                ZimbraLog.extensions.info(e.getMessage());
            }
        } else {
            ZimbraLog.extensions.info("Nextcloud extension received a POST, but AuthToken was invalid.");
        }
    }

    public boolean fetchMail(HttpServletRequest req, AuthToken authToken, String
            accessToken, String Path, String fileName, JSONObject mailObject, Server server) {
        try {
            URL url;
            String uri;
            HttpURLConnection connection = null;
            SardineImpl sardine = new SardineImpl(accessToken);
            ZimbraLog.extensions.info(req.getServerName() + ":" + server.getName());

            if (!"skip".equals(mailObject.getString("id"))) {
                String path = "/service/home/~/?auth=co&id=" + mailObject.getString("id") + "&disp=a";
                uri = URLUtil.getServiceURL(server, path, true);
                ZimbraLog.extensions.info(uri);
                url = new URL(uri);

                connection = (HttpURLConnection) url.openConnection();
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("charset", "utf-8");
                connection.setRequestProperty("Content-Length", "0");
                connection.setRequestProperty("Cookie", "ZM_AUTH_TOKEN=" + authToken.getEncoded() + ";");
                connection.setUseCaches(false);

                if (connection.getResponseCode() == 200) {
                    sardine = new SardineImpl(accessToken);
                    sardine.put(Path + fileName + ".eml", connection.getInputStream());
                } else {
                    throw new Exception("com.zimbra.nextcloud cannot fetch mail item");
                }
            }


            JSONArray attachments = null;
            try {
                attachments = mailObject.getJSONArray("attachments");
            } catch (Exception e) {
                ZimbraLog.extensions.errorQuietly("Error uploading attachment", e);
            }

            //fetch and upload attachments
            if (attachments != null) {
                for (int i = 0; i < attachments.length(); i++) {
                    JSONObject attachment = attachments.getJSONObject(i);
                    String path = "/" + attachment.getString("url") + "&disp=a";
                    uri = URLUtil.getServiceURL(server, path, true);
                    url = new URL(uri);
                    ZimbraLog.extensions.info(uri);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestMethod("GET");
                    connection.setRequestProperty("charset", "utf-8");
                    connection.setRequestProperty("Content-Length", "0");
                    connection.setRequestProperty("Cookie", "ZM_AUTH_TOKEN=" + authToken.getEncoded() + ";");
                    connection.setUseCaches(false);

                    if (connection.getResponseCode() == 200) {
                        sardine = new SardineImpl(accessToken);
                        //having to do a replace for spaces, maybe a bug in Sardine.
                        String attachmentFileName = uriEncode(attachment.getString("filename")).replace("%2F", "/");
                        if (!"skip".equals(mailObject.getString("id"))) {
                            sardine.put(Path + fileName + '-' + attachmentFileName, connection.getInputStream());
                        } else {
                            sardine.put(Path + attachmentFileName, connection.getInputStream());
                        }

                    } else {
                        throw new Exception("com.zimbra.nextcloud cannot fetch attachment");
                    }
                }
            }

            return true;
        } catch (Exception e) {
            ZimbraLog.extensions.info("Error : ", e.getMessage());
            return false;
        }
    }


    /**
     * Perform a PROPFIND request.
     * <p>
     * Implement Custom DAV Property oc:fileid, needed for OnlyOffice and other integrations
     * {http://owncloud.org/ns}fileid The unique id for the file within the instance
     * https://docs.nextcloud.com/server/12/developer_manual/client_apis/WebDAV/index.html
     * <p>
     * Copied from com.zextras.dav->DavSoapConnector.java from ownCloud Zimlet
     */
    public JSONArray propfind(SardineImpl mSardine, String Path)
            throws IOException {
        try {
            final JSONArray arrayResponse = new JSONArray();
            //to-do: check if this breaks WebDAV Servers that do not implement this, aka Alfresco,
            //if it breaks, make it configurable
            Set<QName> CustomProps = new HashSet<QName>();
            CustomProps.add(new QName("http://owncloud.org/ns", "fileid", "oc"));
            CustomProps.add(new QName("DAV:", "getcontentlength", "d"));
            CustomProps.add(new QName("DAV:", "getlastmodified", "d"));
            CustomProps.add(new QName("DAV:", "getcontenttype", "d"));
            CustomProps.add(new QName("DAV:", "resourcetype", "d"));

            List<DavResource> propfind = mSardine.propfind(
                    Path,
                    1,
                    CustomProps
            );

            for (DavResource resource : propfind) {
                JSONObject res = new JSONObject();
                res.put("href", getDAVPath(resource.getPath()));
                if (resource.getCreation() != null) {
                    res.put("creation", resource.getCreation().getTime());
                }
                if (resource.getModified() != null) {
                    res.put("modified", resource.getModified().getTime());
                }
                res.put("contentType", resource.getContentType());
                res.put("contentLength", resource.getContentLength());
                res.put("etag", resource.getEtag());
                res.put("displayName", resource.getDisplayName());

                JSONArray resourceTypes = new JSONArray();
                for (QName name : resource.getResourceTypes()) {
                    resourceTypes.put("{" + name.getNamespaceURI() + "}" + name.getLocalPart());
                }
                res.put("resourceTypes", resourceTypes);
                res.put("contentLanguage", resource.getContentLanguage());
                JSONArray supportedReports = new JSONArray();
                for (QName name : resource.getSupportedReports()) {
                    supportedReports.put("{" + name.getNamespaceURI() + "}" + name.getLocalPart());
                }
                res.put("supportedReports", supportedReports);
                JSONObject customProps = new JSONObject();
                for (String key : resource.getCustomProps().keySet()) {
                    customProps.put(key, resource.getCustomProps().get(key));
                }
                res.put("customProps", customProps);
                arrayResponse.put(res);
            }
            return arrayResponse;
        } catch (Exception e) {
            ZimbraLog.extensions.info(e.getMessage());
            return null;
        }
    }

    public String getDAVPath(String path) {
        String matchFilter = "remote.php/webdav";
        return path.substring(path.lastIndexOf(matchFilter) + matchFilter.length());
    }

    public String uriEncode(String dirty) {
        try {
            String clean = java.net.URLEncoder.encode(dirty, "UTF-8");
            return clean.replaceAll("\\+", "%20");
        } catch (Exception e) {
            ZimbraLog.extensions.info(e.getMessage());
            return null;
        }
    }

    private String uriDecode(String dirty) {
        try {
            String clean = java.net.URLDecoder.decode(dirty, "UTF-8");
            return clean;
        } catch (Exception e) {
            ZimbraLog.extensions.info(e.getMessage());
            return null;
        }
    }

    /* Method copied from https://github.com/zimbra-community/OCS

    example test in javascript:

      let fakeEmailData = {}
      fakeEmailData.nextcloudAction = "createShare";
      fakeEmailData.nextcloudPath = "/Readme.md"; //path to file/folder to share
      fakeEmailData.shareType = "3"; //3 = public link share
      fakeEmailData.password = "";
      var expiryDays = 2; //expire in 2 days
      var expiration = (new Date(Date.now() + expiryDays * 24 * 60 * 60 * 1000)).toISOString().slice(0,10);
      fakeEmailData.expiryDate = ""; //or set to `expiration` variable as above
      fakeEmailData.OCSPath = "https://nextcloudtest.barrydegraaff.tk/nextcloud/ocs/v1.php/apps/files_sharing/api/v1/shares";
      fakeEmailData.nextcloudDAVPath = "";
      var request = new XMLHttpRequest();
      var url = '/service/extension/nextcloud';
      var formData = new FormData();
      formData.append("jsondata", JSON.stringify(fakeEmailData));
      request.open('POST', url);
      request.onreadystatechange = function (e) {
         if (request.readyState == 4) {
            if (request.status == 200) {
               const OCSResponse = JSON.parse(request.responseText);
               console.log(OCSResponse);
            }
            if (request.status == 400) {
               const OCSResponse = JSON.parse(request.responseText);
               console.log(OCSResponse);
            }
         }
      }
      request.send(formData);

      example HTTP 200 - OK response: {"statuscode":100,"id":"7","message":"","url":"https://nextcloudtest.barrydegraaff.tk/nextcloud/index.php/s/W6TxAbq853itsRa","status":"ok","token":""}

      example HTTP 400 error response: {"statuscode":400,"id":0,"message":"","url":"Could not create share. ","status":"ok","token":""}
    */
    public String createShare(String accessToken, String OCSPath, String path, String shareType, String password, String expiryDate, HttpServletResponse resp) {
        try {
            final String urlParameters = "path=" + path + "&shareType=" + shareType + "&password=" + password;

            byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);
            int postDataLength = postData.length;

            URL url = new URL(OCSPath);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("charset", "utf-8");
            conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
            conn.setRequestProperty("OCS-APIRequest", "true");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setUseCaches(false);

            try (DataOutputStream wr = new DataOutputStream(conn.getOutputStream())) {
                wr.write(postData);
            }

            InputStream _is;
            Boolean isError = false;
            if (conn.getResponseCode() < 400) {
                _is = conn.getInputStream();
                isError = false;
            } else {
                _is = conn.getErrorStream();
                isError = true;
            }

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(_is));

            String inputLine;
            StringBuffer responseTxt = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                responseTxt.append(inputLine);
            }
            in.close();

            Pattern pattern;
            if (isError) {
                pattern = Pattern.compile("<message>(.+?)</message>");
            } else {
                pattern = Pattern.compile("<url>(.+?)</url>");
            }
            Matcher matcher = pattern.matcher(responseTxt.toString());
            matcher.find();
            final String result = matcher.group(1);

            if (!isError) {
                pattern = Pattern.compile("<id>(.+?)</id>");
            }
            matcher = pattern.matcher(responseTxt.toString());
            matcher.find();
            final String id = matcher.group(1);

            //Implement Expiry date and update Password
            //And empty password or expiryDate will remove the property from the share
            //https://docs.nextcloud.com/server/12/developer_manual/core/ocs-share-api.html#update-share
            String errorMessage = "";
            try {

                final String[] updateArguments = {"expireDate=" + expiryDate, "password=" + password};
                for (String urlParameter : updateArguments) {
                    postData = urlParameter.getBytes(StandardCharsets.UTF_8);
                    postDataLength = postData.length;
                    String requestUrl = OCSPath + "/" + id;

                    url = new URL(requestUrl);
                    conn = (HttpURLConnection) url.openConnection();

                    conn.setDoOutput(true);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestMethod("PUT");
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setRequestProperty("charset", "utf-8");
                    conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
                    conn.setRequestProperty("OCS-APIRequest", "true");
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                    conn.setUseCaches(false);

                    try (DataOutputStream wr = new DataOutputStream(conn.getOutputStream())) {
                        wr.write(postData);
                    }

                    isError = false;
                    if (conn.getResponseCode() < 400) {
                        _is = conn.getInputStream();
                        isError = false;
                    } else {
                        _is = conn.getErrorStream();
                        isError = true;
                    }

                    in = new BufferedReader(
                            new InputStreamReader(_is));

                    responseTxt = new StringBuffer();

                    while ((inputLine = in.readLine()) != null) {
                        responseTxt.append(inputLine);
                    }
                    in.close();
                    if (!isError) {
                        try {
                            pattern = Pattern.compile("<message>(.+?)</message>");
                            matcher = pattern.matcher(responseTxt.toString());
                            matcher.find();
                            if (!"OK".equals(matcher.group(1))) {
                                errorMessage += matcher.group(1) + ". ";
                            }
                        } catch (Exception e) {
                            //ignore https://github.com/Zimbra-Community/owncloud-zimlet/issues/148
                        }
                    }
                }
            } catch (Exception e) {
                errorMessage += e.toString();
            }

                /*The result variable holds the result from the `Create Share` request. If it starts with http it means the request was OK,
                otherwise it will hold the error message.

                Not all share properties can be set with `Create Share` and creating a share on an object that is already shared, will not
                give an error, but does not do anything.

                Therefore we always do 2 `Update Share` requests as well, to set/remove the password and expiry date.

                The errorMessage variable holds the result from the Update Share` requests, in case it is empty, it means all went OK
                and the result from `Create Share` can be trusted. Otherwise it holds concatenated error messages, that should be displayed
                to the user. The link share url would not be reliable in this case.
                 */
            if ("".equals(errorMessage)) {
                resp.setStatus(200);
                return "{\"statuscode\":100,\"id\":\"" + id + "\",\"message\":\"\",\"url\":\"" + result + "\",\"status\":\"ok\",\"token\":\"\"}";
            } else {
                //result holds the url of the created share, if all went well, or also an error message if the sharing failed
                resp.setStatus(400);
                return "{\"statuscode\":400,\"id\":\"" + id + "\",\"message\":\"\",\"url\":\"" + errorMessage + "\",\"status\":\"ok\",\"token\":\"\"}";
            }
        } catch (
                Exception ex) {
            resp.setStatus(400);
            return "{\"statuscode\":400,\"id\":0,\"message\":\"\",\"url\":\"" + "Could not create share. " + "\",\"status\":\"ok\",\"token\":\"\"}";
        }
    }


    /*
     * Method to send JSON POST requests to Nextcloud
     * New Nextcloud API's are JSON based, in addition this implements ZimbraHttpConnectionManager which is preferred over HttpURLConnection for manageability of future Java changes.
     *
     * example test in javascript (https://nextcloud-talk.readthedocs.io/en/latest/conversation/#creating-a-new-conversation):

      let fakeEmailData = {}
      fakeEmailData.nextcloudAction = "createTalkConv";
      fakeEmailData.body = {"roomType":3,"roomName":"mytest"};
      fakeEmailData.NextcloudApiURL = "https://nextcloud-test.barrydegraaff.nl/nextcloud/ocs/v2.php/apps/spreed/api/v4/room";
      fakeEmailData.nextcloudPath = "/";
      fakeEmailData.nextcloudDAVPath = "";
      var request = new XMLHttpRequest();
      var url = '/service/extension/nextcloud';
      var formData = new FormData();
      formData.append("jsondata", JSON.stringify(fakeEmailData));
      request.open('POST', url);
      request.onreadystatechange = function (e) {
         if (request.readyState == 4) {
            if (request.status == 200) {
               const OCSResponse = JSON.parse(request.responseText);
               console.log(OCSResponse);
            }
            if (request.status == 400) {
               const OCSResponse = JSON.parse(request.responseText);
               console.log(OCSResponse);
            }
         }
      }
      request.send(formData);
     * */
    public String doPostRequestToNextcloud(String accessToken, JSONObject NextcloudRequestJSON, String NextcloudApiURL) {
        try {
            RequestConfig config = RequestConfig.custom().setConnectTimeout(request_timeout).setConnectionRequestTimeout(request_timeout).setSocketTimeout(request_timeout).build();
            HttpClientBuilder clientBuilder = ZimbraHttpConnectionManager.getExternalHttpConnMgr().newHttpClient().setDefaultRequestConfig(config);

            HttpResponse response;
            HttpProxyUtil.configureProxy(clientBuilder);

            //prepare post request
            HttpPost post = new HttpPost(NextcloudApiURL);
            StringEntity requestEntity = new StringEntity(NextcloudRequestJSON.toString(), ContentType.APPLICATION_JSON);
            post.setEntity(requestEntity);
            post.addHeader("OCS-APIRequest", "true");
            post.addHeader("Authorization", "Bearer " + accessToken);
            post.addHeader("Accept", "application/json, text/plain, */*");

            response = HttpClientUtil.executeMethod(clientBuilder.build(), post);

            return EntityUtils.toString(response.getEntity());
        } catch (Exception e) {
            ZimbraLog.extensions.error("doPostRequestToNextcloud failed for %s, %s", NextcloudRequestJSON.toString(), e.getMessage());
            return null;
        }
    }
}
