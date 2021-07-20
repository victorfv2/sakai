/****************************************************************************** 
* Copyright (c) 2020 Apereo Foundation

* Licensed under the Educational Community License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at

*          http://opensource.org/licenses/ecl2

* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
 ******************************************************************************/
package org.sakaiproject.mailsender.tool.controller;


import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.sakaiproject.authz.api.GroupNotDefinedException;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.email.api.Attachment;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.mailsender.AttachmentException;
import org.sakaiproject.mailsender.MailsenderException;
import org.sakaiproject.mailsender.logic.ComposeLogic;
import org.sakaiproject.mailsender.logic.ExternalLogic;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.PreferencesService;
import org.sakaiproject.user.api.User;
import org.sakaiproject.portal.util.PortalUtils;
import org.sakaiproject.mailsender.logic.ConfigLogic;
import org.sakaiproject.mailsender.model.ConfigEntry;
import org.sakaiproject.mailsender.model.EmailEntry;
import org.sakaiproject.mailsender.model.EmailRole;
import org.sakaiproject.util.StringUtil;
import org.sakaiproject.util.Web;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;


@Slf4j
@Controller
public class MainController {

    private Map<String, MultipartFile> multipartMap;
    private MessageSource messageSource;
    private ServerConfigurationService configService;

    @Autowired
    private SessionManager sessionManager;
    
    @Autowired
    private PreferencesService preferencesService;
    
    @Autowired
    private ExternalLogic externalLogic;
    
    @Autowired
    private ConfigLogic configLogic;
    
    @Autowired
    private ComposeLogic composeLogic;
    
    
    private Locale localeResolver(HttpServletRequest request, HttpServletResponse response) {
        String userId = sessionManager.getCurrentSessionUserId();
        final Locale loc = StringUtils.isNotBlank(userId) ? preferencesService.getLocale(userId) : Locale.getDefault();
        LocaleResolver localeResolver = RequestContextUtils.getLocaleResolver(request);
        localeResolver.setLocale(request, response, loc);
        return loc;
    }
    private void addEmailUsers(String fromEmail, HashMap<String, String> emailusers,
			List<User> users)
	{
		for (User user : users)
		{
			addEmailUser(fromEmail, emailusers, user);
		}
	}
    
    private void addEmailUser(String fromEmail, HashMap<String, String> emailusers,
			User user)
	{
		if (!fromEmail.equals(user.getEmail()))
		{
			emailusers.put(user.getEmail(), user.getDisplayName());
		}
	}

   private HashSet<String> compileEmailList(EmailEntry emailEntry,String fromEmail, HashMap<String, String> emailusers)
	{
		HashSet<String> badEmails = new HashSet<String>();
		if (emailEntry.isAllIds()) {
			try
			{
				addEmailUsers(fromEmail, emailusers, composeLogic.getUsers());
			}
			catch (IdUnusedException e)
			{
				log.warn(e.getMessage(), e);
				badEmails.add(e.getMessage());
			}
		} else {
			// check for roles and add users
			for (String roleId : emailEntry.getRoleIds().keySet())
			{
				try
				{
					List<User> users = composeLogic.getUsersByRole(roleId);
					addEmailUsers(fromEmail, emailusers, users);
				}
				catch (IdUnusedException e)
				{
					log.warn(e.getMessage(), e);
					badEmails.add(roleId);
				}
			}

			// check for sections and add users
			for (String sectionId : emailEntry.getSectionIds().keySet())
			{
				try
				{
					List<User> users = composeLogic.getUsersByGroup(sectionId);
					addEmailUsers(fromEmail, emailusers, users);
				}
				catch (IdUnusedException e)
				{
					log.warn(e.getMessage(), e);
				}
			}

			// check for groups and add users
			for (String groupId : emailEntry.getGroupIds().keySet())
			{
				try
				{
					List<User> users = composeLogic.getUsersByGroup(groupId);
					addEmailUsers(fromEmail, emailusers, users);
				}
				catch (IdUnusedException e)
				{
					log.warn(e.getMessage(), e);
				}
			}

			for (String userId : emailEntry.getUserIds().keySet())
			{
                            
				User user = externalLogic.getUser(userId);
				addEmailUser(fromEmail, emailusers, user);
			}
		}
		return badEmails;
                
	}
    @RequestMapping(value = {"/","/compose"})
    public String showCompose(Model model, HttpServletRequest request, HttpServletResponse response){
       try {
           
        Locale loc =localeResolver(request,response);
        // get the user then name & email
        User curUser = externalLogic.getCurrentUser();
       
        String fromEmail = "";
        String fromDisplay = "";
        if (curUser != null) {
            fromEmail = curUser.getEmail();
            fromDisplay = curUser.getDisplayName();
        }
        String from = fromDisplay + " <" + fromEmail + ">";
        ConfigEntry confi=configLogic.getConfig();
        model.addAttribute("username",from);
        model.addAttribute("added",externalLogic.isEmailArchiveAddedToSite());
        model.addAttribute("config",confi);
        model.addAttribute("comp",composeLogic);
        model.addAttribute("siteID",externalLogic.getSiteID());
        model.addAttribute("cdnQuery", PortalUtils.getCDNQuery());
        model.addAttribute("sakaiHtmlHead", (String) request.getAttribute("sakai.html.head"));
        List<EmailRole> list= composeLogic.getEmailSections();
        HashMap<String, String> emailusers= new HashMap<>();
       } catch (IdUnusedException ex) {
            Logger.getLogger(MainController.class.getName()).log(Level.SEVERE, null, ex);
        }
        return "compose";
    }

     @RequestMapping(value = {"/options"})
    public String showOptions(Model model, HttpServletRequest request, HttpServletResponse response) {
               
        Locale loc=localeResolver(request,response);
        
        ConfigEntry opconf =configLogic.getConfig();
        
        model.addAttribute("added",externalLogic.isEmailArchiveAddedToSite());
        model.addAttribute("prefix",configLogic.allowSubjectPrefixChange());
        model.addAttribute("config",opconf);
        model.addAttribute("siteID",externalLogic.getSiteID());

        return "options";
    }
    
    @RequestMapping(value = {"/options"}, method = RequestMethod.POST)
    public String submitOptions(Model model, HttpServletRequest request, HttpServletResponse response) {
               
        Locale loc =localeResolver(request, response);
        
        
        ConfigEntry confi=configLogic.getConfig();
        confi.setSendMeACopy(Boolean.parseBoolean(request.getParameter("sendMeACopy")));
        confi.setAddToArchive(Boolean.parseBoolean(request.getParameter("addToArchive")));
        confi.setAppendRecipientList(Boolean.parseBoolean(request.getParameter("appendRecipientList")));
        confi.setReplyTo(request.getParameter("replyto"));
        confi.setDisplayInvalidEmails(Boolean.parseBoolean(request.getParameter("InvalidEm")));
        confi.setDisplayEmptyGroups(Boolean.parseBoolean(request.getParameter("rcpsempty")));
        if(request.getParameter("prefix").equals("custom")||!request.getParameter("subjectPrefix").isEmpty()){
            confi.setSubjectPrefixType(request.getParameter("prefix"));
            confi.setSubjectPrefix(request.getParameter("subjectPrefix"));}
        else{
            confi.setSubjectPrefixType(request.getParameter("prefix"));
        }        
        return "redirect:/compose";
    }
    
    
    @RequestMapping(value = {"/permissions"})
    public String showPermissions(Model model, HttpServletRequest request, HttpServletResponse response) {
               
        String userId = sessionManager.getCurrentSessionUserId();
        final Locale locale = StringUtils.isNotBlank(userId) ? preferencesService.getLocale(userId) : Locale.getDefault();
        LocaleResolver localeResolver = RequestContextUtils.getLocaleResolver(request);
        localeResolver.setLocale(request, response, locale);

        return "permissions";
    }
    
   @RequestMapping(value = {"/compose"}, method = RequestMethod.POST)
    public String submitCompose(Model model, HttpServletRequest request, HttpServletResponse response)  {
                     
       try {        
        Locale loc=localeResolver(request,response);
        // get the user then name & email
        User curUser = externalLogic.getCurrentUser();
       
        String fromEmail = "";
        String fromDisplay = "";
        if (curUser != null) {
            fromEmail = curUser.getEmail();
            fromDisplay = curUser.getDisplayName();
        }
        
        if (fromEmail == null || fromEmail.trim().length() == 0)
		{
			messageSource.getMessage("no.from.address",null ,loc);
		}
        String from = fromDisplay + " <" + fromEmail + ">";
        ConfigEntry confi=configLogic.getConfig();
        EmailEntry emailEntrytest= new EmailEntry(confi);
        emailEntrytest.setContent(request.getParameter("editor1"));
        emailEntrytest.setSubject(request.getParameter("subject"));
        emailEntrytest.setAllIds(Boolean.parseBoolean(request.getParameter("rcptsall")));
        confi.setSendMeACopy(Boolean.parseBoolean(request.getParameter("sendMeACopy")));
        confi.setAddToArchive(Boolean.parseBoolean(request.getParameter("addToArchive")));
        confi.setAppendRecipientList(Boolean.parseBoolean(request.getParameter("appendRecipientList")));
        
        if(request.getParameter("otherRecipients")!=null){
        emailEntrytest.setOtherRecipients(request.getParameter("otherRecipients"));}
           
        
        String[] rolarr,sectionarr,grouparr,userarr;
        
        Map<String, String> rolesIds = new HashMap<String, String>();
        Map<String, String> sectionsIds = new HashMap<String, String>();
        Map<String, String> groupsIds = new HashMap<String, String>();
        Map<String, String> usersIds = new HashMap<String, String>();
        
        if(request.getParameterValues("rolename")!=null){
            rolarr= request.getParameterValues("rolename");
            for(String role : rolarr){
                rolesIds.put(role, role);
            }
            emailEntrytest.setRoleIds(rolesIds);
        }
        
        if(request.getParameterValues("rolegname")!=null){
            grouparr = request.getParameterValues("rolegname");
            for(String group : grouparr){
                groupsIds.put(group, group);
            }
            emailEntrytest.setGroupIds(groupsIds);
        }
        
        if(request.getParameterValues("rolesecname")!=null){
            sectionarr= request.getParameterValues("rolesecname");
            for(String section : sectionarr){
                sectionsIds.put(section, section);
            }
            emailEntrytest.setSectionIds(sectionsIds);
        }
        
        if(request.getParameterValues("user")!=null){
            userarr= request.getParameterValues("user");
            for(String user : userarr){
                usersIds.put(user, user);
            }
            emailEntrytest.setUserIds(usersIds);
        }
         HashMap<String, String> emailusers= new HashMap<>();
        compileEmailList(emailEntrytest,fromEmail, emailusers);      
        }
        catch (IllegalStateException ex) {
            Logger.getLogger(MainController.class.getName()).log(Level.SEVERE, null, ex);
        }
        return "redirect:/compose";
        
    }
}