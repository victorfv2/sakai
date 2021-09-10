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
import java.util.Arrays;
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
import org.sakaiproject.util.Web;
import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Controller
public class MainController {
    
    private MessageSource messageSource;
    
    @Autowired
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
    
    HashMap<String, String> emailusersmap= new HashMap<>();
    
    private ArrayList<String> errors,info= new ArrayList<>();
    
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
            if (!StringUtils.isEmpty(user.getEmail())){
		if (!fromEmail.equals(user.getEmail()))
		{
			emailusers.put(user.getEmail(), user.getDisplayName());
		}
            }
	}

   private HashSet<String> compileEmailList(EmailEntry emailEntry,String fromEmail, HashMap<String, String> emailusers)
	{
		HashSet<String> badEmails = new HashSet<>();
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
                    emailEntry.getRoleIds().keySet().forEach(roleId -> {
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
                    });

                    // check for sections and add users
                    emailEntry.getSectionIds().keySet().forEach(sectionId -> {
                        try
                        {
                            List<User> users = composeLogic.getUsersByGroup(sectionId);
                            addEmailUsers(fromEmail, emailusers, users);
                        }
                        catch (IdUnusedException e)
                        {
                            log.warn(e.getMessage(), e);
                        }
                    });

                    // check for groups and add users
                    emailEntry.getGroupIds().keySet().forEach(groupId -> {
                        try
                        {
                            List<User> users = composeLogic.getUsersByGroup(groupId);
                            addEmailUsers(fromEmail, emailusers, users);
                        }
                        catch (IdUnusedException e)
                        {
                            log.warn(e.getMessage(), e);
                        }
                    });

                    emailEntry.getUserIds().keySet().stream().map(userId -> {
                        System.out.println(">>>Adding user by id: "+userId);
                        return userId;
                    }).map(userId -> externalLogic.getUser(userId)).map(user -> {
                        System.out.println("* "+((user!= null) ? user.getEmail() : "-null-"));
                        return user;
                    }).forEachOrdered(user -> {
                        addEmailUser(fromEmail, emailusers, user);
                    });
		}
                System.out.println("Email users list.......................");
                for(String key : emailusers.keySet()){
                        System.out.println("*"+key+"->"+emailusers.get(key));
                }
                System.out.println("............................................");
		return badEmails;             
	}
   
   private String compileRecipientList(Map<String, String> recipients,Locale loc)
	{
		StringBuilder recipientList = new StringBuilder();
		recipientList.append("<br>");
		recipientList.append(messageSource.getMessage("message.sent.to",null,loc));
		Iterator iter = recipients.entrySet().iterator();
		while (iter.hasNext())
		{
			Map.Entry<String, String> entry = (Map.Entry)iter.next();
			String email = entry.getKey();
			String name = entry.getValue();
			if (name != null)
			{
				recipientList.append(name);
			}
			else
			{
				recipientList.append(email);
			}
			if (iter.hasNext()) recipientList.append(", "); 
		}
		
		return recipientList.toString();
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
        model.addAttribute("email",StringUtils.isAllEmpty(fromEmail));
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
        configLogic.saveConfig(confi);
        return "redirect:/compose";
    }
    
    
    @RequestMapping(value = {"/permissions"})
    public String showPermissions(Model model, HttpServletRequest request, HttpServletResponse response) {
        
        Locale loc =localeResolver(request, response);

        return "permissions";
    }
    
    @RequestMapping(value = {"/results"})
    public String showResults(Model model, HttpServletRequest request, HttpServletResponse response) {
       
        Locale loc =localeResolver(request, response);
        //model.addAttribute("emails",emailusersmap.keySet().toArray()[0]);
        return "results";
    }
    
    private void addToArchive(EmailEntry emailEntry, ConfigEntry config, MultipartFile[] files,String fromString, String subject, String siteId, List<Attachment> attachments)
	{
		if (emailEntry.getConfig().isAddToArchive())
		{
			StringBuilder attachment_info = new StringBuilder("<br/>");
			int i = 1;
                        if(files.length!=0){
                            for (MultipartFile file : files){
				if (file.getSize() > 0)
				{
					attachment_info.append("<br/>");
					attachment_info.append("Attachment #").append(i).append(": ").append(
							Web.escapeHtml(file.getOriginalFilename())).append("(").append(file.getSize()).append(" Bytes)");
					i++;
				}
                            }
                        }
			String emailarchive = "/mailarchive/channel/" + siteId + "/main";
			String content = Web.cleanHtml(emailEntry.getContent()) + attachment_info.toString();
			externalLogic.addToArchive(config, emailarchive, fromString, subject, content, attachments);
		}
	}
    
   @RequestMapping(value = {"/compose"}, method = RequestMethod.POST)
    public String submitCompose(@RequestParam("attachment") MultipartFile[] files,Model model, HttpServletRequest request, HttpServletResponse response) throws AttachmentException, MailsenderException  {
                     
              
        Locale loc=localeResolver(request,response);
        // get the user then name & email
        User curUser = externalLogic.getCurrentUser();
       
        String fromEmail = "";
        String fromDisplay = "";
        if (curUser != null) {
            fromEmail = curUser.getEmail();
            fromDisplay = curUser.getDisplayName();
        }
        String from = fromDisplay + " <" + fromEmail + ">";
        ConfigEntry config=configLogic.getConfig();
        EmailEntry emailEntry= new EmailEntry(config);
        emailEntry.setContent(request.getParameter("editor1"));
        emailEntry.setSubject(request.getParameter("subject"));
        emailEntry.setAllIds(Boolean.parseBoolean(request.getParameter("rcptsall")));
        config.setSendMeACopy(Boolean.parseBoolean(request.getParameter("sendMeACopy")));
        config.setAddToArchive(Boolean.parseBoolean(request.getParameter("addToArchive")));
        config.setAppendRecipientList(Boolean.parseBoolean(request.getParameter("appendRecipientList")));
        emailEntry.setConfig(config);
        
        if(request.getParameter("otherRecipients")!=null){
        emailEntry.setOtherRecipients(request.getParameter("otherRecipients"));}
        
        String[] rolarr,sectionarr,grouparr,userarr;
        
        Map<String, String> rolesIds = new HashMap<>();
        Map<String, String> sectionsIds = new HashMap<>();
        Map<String, String> groupsIds = new HashMap<>();
        Map<String, String> usersIds = new HashMap<>();
        
        if(!Boolean.parseBoolean(request.getParameter("rcptsall"))){
            if(request.getParameterValues("rolename")!=null){
                rolarr= request.getParameterValues("rolename");
                for(String role : rolarr){
                    rolesIds.put(role, role);
                }   
                emailEntry.setRoleIds(rolesIds);
            }
        
            if(request.getParameterValues("rolegname")!=null){
                grouparr = request.getParameterValues("rolegname");
                for(String group : grouparr){
                    System.out.println();
                    groupsIds.put(group, group);
                }
                emailEntry.setGroupIds(groupsIds);
            }
        
            if(request.getParameterValues("rolesecname")!=null){
                sectionarr= request.getParameterValues("rolesecname");
                for(String section : sectionarr){
                    sectionsIds.put(section, section);
                }
                emailEntry.setSectionIds(sectionsIds);
            }
        }
        
        if(request.getParameterValues("user")!=null){
            userarr= request.getParameterValues("user");
            for(String user : userarr){
                usersIds.put(user, user);
            }
            emailEntry.setUserIds(usersIds);
        }
        HashMap<String, String> emailusers = new HashMap<>();

		// compile the list of emails to send to
		compileEmailList(emailEntry,fromEmail, emailusers);

		// handle the other recipients
		List<String> emailOthers = emailEntry.getOtherRecipients();
		String[] allowedDomains = StringUtils.split(configService.getString("sakai.mailsender.other.domains"), ",");;
		
		List<String> invalids = new ArrayList<>();
		// add other recipients to the message
		for (String email : emailOthers){
			if (allowedDomains != null && allowedDomains.length > 0){
				// check each "other" email to ensure it ends with an accepts domain
				for (String domain : allowedDomains){
					if (email.endsWith(domain)){
						emailusers.put(email, null);
					}else{
						invalids.add(email);
					}
				}
			}else{
				emailusers.put(email, null);
			}
		}

		String content = emailEntry.getContent();
                //ystem.out.println("funciona"+emailEntry.getConfig().isAppendRecipientList()+" "+compileRecipientList(emailusers,loc));
		if (emailEntry.getConfig().isAppendRecipientList()) {
		    content = content + compileRecipientList(emailusers,loc);
		}

		String subjectContent = emailEntry.getSubject();
		if (subjectContent == null || subjectContent.trim().length() == 0) {
			subjectContent = messageSource.getMessage("no.subject",null,loc);
		}

		String subject = ((config.getSubjectPrefix() != null) ? config.getSubjectPrefix() : "")+ subjectContent;
                try{
                    if (invalids.isEmpty()){
				List<Attachment> attachments = new ArrayList<>();
				if (files != null && files.length!=0){
					for(MultipartFile mf:files){
						// Although JavaDoc says it may contain path, Commons implementation always just
						// returns the filename without the path.
                                            String filename = Web.escapeHtml(mf.getOriginalFilename());
                                            
                                            try {
                                                File f = File.createTempFile(filename, null);
                                                mf.transferTo(f);
                                                Attachment attachment = new Attachment(f, filename);
                                                attachments.add(attachment);
                                                System.out.println(attachments.get(0).getFilename()); 
                                            
                                                }catch (IOException ioe) {
                                                    throw new AttachmentException(ioe.getMessage());
                                                }
                                        }
                                }
                                
				// send the message
				invalids = externalLogic.sendEmail(config, fromEmail, fromDisplay,emailusers, subject, content, attachments);
				// append to the email archive
				String siteId = externalLogic.getSiteID();
				String fromString = fromDisplay + " <" + fromEmail + ">";
				addToArchive(emailEntry, config, files, fromString, subject, siteId, attachments);
                        }
                        
			// build output message for results screen
			for (Entry<String, String> entry : emailusers.entrySet()){
				String compareAddr = null;
				String addrStr = null;
				if (entry.getValue() != null && entry.getValue().trim().length() > 0)
				{
					addrStr = entry.getValue();
					compareAddr = "\"" + entry.getValue() + "\" <" + entry.getKey() + ">";
				}else{
					addrStr = entry.getKey();
					compareAddr = entry.getKey();
				}
                                
                                if (!invalids.contains(compareAddr)){
					/*messages.addMessage(new TargettedMessage("verbatim", new String[] { addrStr },
							TargettedMessage.SEVERITY_CONFIRM));*/
				}
			}
		}catch (MailsenderException me){
			//Print this exception
			log.warn(me.getMessage());
			List<Map<String, Object[]>> msgs = me.getMessages();
			if (msgs != null)
			{
				for (Map<String, Object[]> msg : msgs)
				{
					for(Map.Entry<String, Object[]> e : msg.entrySet())
					{
                                            System.out.println((e.getKey()+"  "+e.getValue()));
                                            errors.add((messageSource.getMessage(e.getKey(),e.getValue(),loc)));
					}
				}
			}
			else
			{
				errors.add(messageSource.getMessage("verbatim",new String[] { me.getMessage() },loc));
			}
                        throw me;
		}
		catch (AttachmentException ae)
		{
			errors.add(messageSource.getMessage("error.attachment", new String[] { ae.getMessage() }, loc));
			return "hola";
		}

		// Display Users with Bad Emails if the option is turned on.
		boolean showBadEmails = config.isDisplayInvalidEmails();
		if (showBadEmails && invalids != null && invalids.size() > 0)
		{
			// add the message for the result screen
			String names = invalids.toString();
			info.add(messageSource.getMessage("invalid.email.addresses",new String[] { names.substring(1, names.length() - 1) },loc));
		}
        return "redirect:/compose";
        }
}