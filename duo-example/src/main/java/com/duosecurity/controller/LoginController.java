package com.duosecurity.controller;


import com.duosecurity.Client;
import com.duosecurity.DuoProperties;
import com.duosecurity.exception.DuoException;
import com.duosecurity.model.Token;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;


/**
 * The Login Controller.
 */
@Controller
public class LoginController {
  private static final String JSP_INDEX = "index";
  private static final String JSP_WELCOME = "welcome";
  private static final String ATTR_MESSAGE = "message";
  private final Map<String, String> stateMap = new HashMap<>();
  private final Client duoClient;
  private final ObjectMapper objectMapper;
  private final DuoProperties duoProperties;

  /**
   * Constructor for LoginController.
   *
   * @param duoClient the duoClient
   * @param objectMapper jackson ObjectMapper
   * @param duoProperties DuoClient configuration properties
   */
  public LoginController(Client duoClient, ObjectMapper objectMapper, DuoProperties duoProperties) {
    this.duoClient = duoClient;
    this.objectMapper = objectMapper;
    this.duoProperties = duoProperties;
  }


  /**
   * Main Landing page.
   *
   * @return The index view
   */
  @GetMapping(value = "/")
  public String index() {
    return JSP_INDEX;
  }

  /**
   * POST handler for login page.
   *
   * @param username        The username of the user trying to authenticate
   * @param password        The password of the user trying to authenticate
   *
   * @return ModelAndView   A model that contains information about where to redirect next
   */
  @PostMapping(value = "/")
  public ModelAndView login(@RequestParam String username, @RequestParam String password)
      throws DuoException {
    // Perform fake primary authentication
    if (!validateUser(username, password)) {
      ModelAndView model = new ModelAndView(JSP_INDEX);
      model.addObject(ATTR_MESSAGE, "Invalid Credentials");
      return model;
    }

    // Step 2: Call Duo health check
    try {
      duoClient.healthCheck();
    } catch (DuoException exception) {
      // If the health check failed AND the integration is configured to fail open then render
      // the welcome page.  If the integarion is configured to fail closed return an error
      if ("OPEN".equalsIgnoreCase(duoProperties.failMode())) {
        ModelAndView model = new ModelAndView(JSP_WELCOME);
        model.addObject("token", "Login Successful, but 2FA Not Performed."
                + "Confirm application.yaml values are correct and that Duo is reachable");
        return model;
      } else {
        ModelAndView model = new ModelAndView(JSP_INDEX);
        model.addObject(ATTR_MESSAGE, "2FA Unavailable."
                + "Confirm application.yaml values are correct and that Duo is reachable");
        return model;
      }
    }

    // Step 3: Generate and save a state variable
    String state = duoClient.generateState();
    // Store the state to remember the session and username
    stateMap.put(state, username);

    // Step 4: Create the authUrl and redirect to it
    String authUrl = duoClient.createAuthUrl(username, state);
    return new ModelAndView("redirect:" + authUrl);

  }

  /**
   * GET handler for duo-callback page.
   *
   * @param duoCode    An authentication session transaction id
   * @param state   A random string returned from Duo used to maintain state
   *
   * @return ModelAndView   A model that contains information about where to redirect next
   */
  @GetMapping(value = "/duo-callback")
  public ModelAndView duoCallback(@RequestParam("duo_code") String duoCode,
                                  @RequestParam("state") String state) throws DuoException {
    // Step 5: Validate state returned from Duo is the same as the one saved previously.
    // If it isn't return an error
    if (!stateMap.containsKey(state)) {
      ModelAndView model = new ModelAndView(JSP_INDEX);
      model.addObject(ATTR_MESSAGE, "Session Expired");
      return model;
    }
    // Remove state from the list of valid sessions
    String username = stateMap.remove(state);

    // Step 6: Exchange the auth duoCode for a Token object
    Token token = duoClient.exchangeAuthorizationCodeFor2FAResult(duoCode, username);

    // If the auth was successful, render the welcome page otherwise return an error
    if (authWasSuccessful(token)) {
      ModelAndView model = new ModelAndView(JSP_WELCOME);
      model.addObject("token", tokenToJson(token));
      return model;
    } else {
      ModelAndView model = new ModelAndView(JSP_INDEX);
      model.addObject(ATTR_MESSAGE, "2FA Failed");
      return model;
    }
  }

  private String tokenToJson(Token token) throws DuoException {
    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(token);
    } catch (JsonProcessingException jpe) {
      throw new DuoException("Could not convert token to JSON");
    }
  }

  /**
   * Exception handler.
   *
   * @param ex  A DuoException
   *
   * @return ModelAndView   A model object that contains the error message from ex
   */
  @ExceptionHandler({DuoException.class})
  public ModelAndView handleException(DuoException ex) {
    ModelAndView model = new ModelAndView(JSP_INDEX);
    model.addObject(ATTR_MESSAGE, ex.getMessage());
    return model;
  }

  private boolean validateUser(String username, String password) {
    return username != null && !username.isBlank()
        && password != null && !password.isBlank();
  }

  private boolean authWasSuccessful(Token token) {
    if (token != null && token.getAuth_result() != null) {
      return "ALLOW".equalsIgnoreCase(token.getAuth_result().getStatus());
    }
    return false;
  }
}
