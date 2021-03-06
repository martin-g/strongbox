package org.carlspring.strongbox.security.jaas.authentication;

import org.carlspring.strongbox.security.jaas.Credentials;
import org.carlspring.strongbox.security.jaas.User;
import org.carlspring.strongbox.security.jaas.principal.BasePrincipal;
import org.carlspring.strongbox.security.jaas.principal.RolePrincipal;
import org.carlspring.strongbox.security.jaas.principal.UserPrincipal;

import javax.security.auth.DestroyFailedException;
import javax.security.auth.Subject;
import javax.security.auth.callback.*;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * <p> This LoginModule authenticates users with a password against a database.
 * <p/>
 * <p> If user is successfully authenticated,
 * a <code>UserPrincipal</code> with the user's user name
 * is added to the Subject.
 * <p/>
 * <p> This LoginModule recognizes the debug option.
 * If set to true in the login Configuration,
 * debug messages will be output to the output stream, System.out.
 */
@Component
public abstract class BaseLoginModule
        implements LoginModule, ApplicationContextAware
{

    private static Logger logger = LoggerFactory.getLogger(BaseLoginModule.class);

    private static ApplicationContext applicationContext;

    // initial state
    private Subject subject;

    private CallbackHandler callbackHandler;

    private Map sharedState;

    private Map options;

    // configurable option
    // private boolean debug = false;

    // the authentication status
    private boolean succeeded = false;

    private boolean commitSucceeded = false;

    private BasePrincipal principal = new BasePrincipal();

    private Credentials credentials = new Credentials();

    private User user;

    private UserAuthenticator userAuthenticator;


    /**
     * Initialize this <code>LoginModule</code>.
     * <p/>
     * <p/>
     *
     * @param subject         the <code>Subject</code> to be authenticated. <p>
     * @param callbackHandler a <code>CallbackHandler</code> for communicating
     *                        with the end user (prompting for user names and
     *                        passwords, for example). <p>
     * @param sharedState     shared <code>LoginModule</code> state. <p>
     * @param options         options specified in the login
     *                        <code>Configuration</code> for this particular
     *                        <code>LoginModule</code>.
     */
    @Override
    public void initialize(Subject subject,
                           CallbackHandler callbackHandler,
                           Map<String, ?> sharedState,
                           Map<String, ?> options)
    {
        this.subject = subject;
        this.callbackHandler = callbackHandler;
        this.sharedState = sharedState;
        this.options = options;

        // initialize any configured options
        // debug = "true".equalsIgnoreCase((String) options.get("debug"));

        userAuthenticator = (UserAuthenticator) applicationContext.getBean("userAuthenticator");
    }

    /**
     * Authenticate the user by prompting for a user name and password.
     * <p/>
     * <p/>
     *
     * @return true in all cases since this <code>LoginModule</code>
     * should not be ignored.
     * @throws javax.security.auth.login.FailedLoginException if the authentication fails. <p>
     * @throws javax.security.auth.login.LoginException       if this <code>LoginModule</code>
     *                                                        is unable to perform the authentication.
     */
    @Override
    public boolean login()
            throws LoginException
    {
        // prompt for a user name and password
        if (callbackHandler == null)
        {
            throw new LoginException("Error: No CallbackHandler available to garner" +
                                     " authentication information from the user!");
        }

        Callback[] callbacks = new Callback[2];
        callbacks[0] = new NameCallback("Username: ");
        callbacks[1] = new PasswordCallback("Password: ", false);

        try
        {
            callbackHandler.handle(callbacks);

            final NameCallback nameCallback = (NameCallback) callbacks[0];
            final PasswordCallback passwordCallback = (PasswordCallback) callbacks[1];

            logger.debug("Handling login for " + nameCallback.getName() + "...");

            principal.setName(nameCallback.getName());
            credentials.setPassword(String.valueOf(passwordCallback.getPassword()));

            passwordCallback.clearPassword();
        }
        catch (IOException ioe)
        {
            throw new LoginException(ioe.toString());
        }
        catch (UnsupportedCallbackException uce)
        {
            throw new LoginException("Error: " + uce.getCallback().toString() +
                                     " not available to garner authentication information " +
                                     "from the user");
        }

        checkUserCredentials(principal.getName(), credentials.getPassword());

        if (user != null)
        {
            logger.debug("Authentication succeeded.");

            succeeded = true;
            return true;
        }
        else
        {
            // authentication failed -- clean out state
            logger.debug("Authentication failed for user " + principal.getName() + ".");

            succeeded = false;

            clearCredentials();

            throw new FailedLoginException("Incorrect username or password!");
        }
    }

    public abstract void checkUserCredentials(String username,
                                              String password)
            throws LoginException;

    /**
     * <p> This method is called if the LoginContext's
     * overall authentication succeeded
     * (the relevant REQUIRED, REQUISITE, SUFFICIENT and OPTIONAL LoginModules
     * succeeded).
     * <p/>
     * <p> If this LoginModule's own authentication attempt
     * succeeded (checked by retrieving the private state saved by the
     * <code>login</code> method), then this method associates a
     * <code>SamplePrincipal</code>
     * with the <code>Subject</code> located in the
     * <code>LoginModule</code>.  If this LoginModule's own
     * authentication attempted failed, then this method removes
     * any state that was originally saved.
     * <p/>
     * <p/>
     *
     * @return true if this LoginModule's own login and commit
     * attempts succeeded, or false otherwise.
     * @throws javax.security.auth.login.LoginException if the commit fails.
     */
    @Override
    public boolean commit()
            throws LoginException
    {
        if (!succeeded)
        {
            logger.debug("Committing failure!");

            return false;
        }
        else
        {
            logger.debug("Committing success!");

            addPrincipals();

            // in any case, clean out state
            clearCredentials();

            commitSucceeded = true;
            return true;
        }
    }

    public void addPrincipals()
    {
        // Add a Principal (authenticated identity) to the Subject
        // Add a principal with the username
        principal = new UserPrincipal(principal.getName());
        if (!subject.getPrincipals().contains(principal))
        {
            subject.getPrincipals().add(principal);

            if (logger.isDebugEnabled())
            {
                logger.debug("Added UserPrincipal [" + principal.toString() + "] to subject.");
            }
        }

        // Add all the roles as principals:
        for (String role : user.getRoles())
        {
            Principal rolePrincipal = new RolePrincipal(role);

            subject.getPrincipals().add(rolePrincipal);

            if (logger.isDebugEnabled())
            {
                logger.debug("Added RolePrincipal [" + rolePrincipal.toString() + "] to subject.");
            }

        }
    }

    /**
     * <p> This method is called if the LoginContext's
     * overall authentication failed.
     * (the relevant REQUIRED, REQUISITE, SUFFICIENT and OPTIONAL LoginModules
     * did not succeed).
     * <p/>
     * <p> If this LoginModule's own authentication attempt
     * succeeded (checked by retrieving the private state saved by the
     * <code>login</code> and <code>commit</code> methods),
     * then this method cleans up any state that was originally saved.
     * <p/>
     * <p/>
     *
     * @return false if this LoginModule's own login and/or commit attempts
     * failed, and true otherwise.
     * @throws javax.security.auth.login.LoginException if the abort fails.
     */
    @Override
    public boolean abort()
            throws LoginException
    {
        logger.debug("Aborting!");

        if (!succeeded)
        {
            return false;
        }
        else if (succeeded && !commitSucceeded)
        {
            // login succeeded but overall authentication failed
            succeeded = false;

            clearCredentials();

            principal = null;
        }
        else
        {
            // overall authentication succeeded and commit succeeded,
            // but someone else's commit failed
            logout();
        }
        return true;
    }

    public void clearCredentials()
    {
        try
        {
            credentials.destroy();
        }
        catch (DestroyFailedException e)
        {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * Logout the user.
     * <p/>
     * <p> This method removes the <code>UserPrincipal</code>
     * that was added by the <code>commit</code> method.
     * <p/>
     * <p/>
     *
     * @return true in all cases since this <code>LoginModule</code>
     * should not be ignored.
     * @throws javax.security.auth.login.LoginException if the logout fails.
     */
    @Override
    public boolean logout()
            throws LoginException
    {
        logger.debug("Logging out!");

        subject.getPrincipals().remove(principal);
        succeeded = commitSucceeded;

        clearCredentials();

        principal = null;
        return true;
    }

    public Subject getSubject()
    {
        return subject;
    }

    public void setSubject(Subject subject)
    {
        this.subject = subject;
    }

    public CallbackHandler getCallbackHandler()
    {
        return callbackHandler;
    }

    public void setCallbackHandler(CallbackHandler callbackHandler)
    {
        this.callbackHandler = callbackHandler;
    }

    public Map getSharedState()
    {
        return sharedState;
    }

    public void setSharedState(Map sharedState)
    {
        this.sharedState = sharedState;
    }

    public Map getOptions()
    {
        return options;
    }

    public void setOptions(Map options)
    {
        this.options = options;
    }

    public boolean isSucceeded()
    {
        return succeeded;
    }

    public void setSucceeded(boolean succeeded)
    {
        this.succeeded = succeeded;
    }

    public boolean isCommitSucceeded()
    {
        return commitSucceeded;
    }

    public void setCommitSucceeded(boolean commitSucceeded)
    {
        this.commitSucceeded = commitSucceeded;
    }

    public BasePrincipal getPrincipal()
    {
        return principal;
    }

    public void setPrincipal(BasePrincipal principal)
    {
        this.principal = principal;
    }

    public Credentials getCredentials()
    {
        return credentials;
    }

    public void setCredentials(Credentials credentials)
    {
        this.credentials = credentials;
    }

    public User getUser()
    {
        return user;
    }

    public void setUser(User user)
    {
        this.user = user;
    }

    public UserAuthenticator getUserAuthenticator()
    {
        return userAuthenticator;
    }

    public void setUserAuthenticator(UserAuthenticator userAuthenticator)
    {
        this.userAuthenticator = userAuthenticator;
    }

    public ApplicationContext getApplicationContext()
    {
        return applicationContext;
    }

    public void setApplicationContext(ApplicationContext applicationContext)
    {
        this.applicationContext = applicationContext;
    }

}
