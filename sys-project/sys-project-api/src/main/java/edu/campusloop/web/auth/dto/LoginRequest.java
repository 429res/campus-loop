package edu.campusloop.web.auth.dto;
import jakarta.validation.constraints.*;
public record LoginRequest(@NotBlank @Size(max=254) String username,@NotBlank @Size(max=72) String password,@Size(max=64) String captchaId,@Size(max=10) String captchaAnswer){
 public LoginRequest(String username,String password){this(username,password,null,null);}
}
