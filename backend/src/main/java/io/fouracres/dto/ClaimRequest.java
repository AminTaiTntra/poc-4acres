package io.fouracres.dto;

public class ClaimRequest {
    private String stewardName;
    private String stewardEmail;

    public String getStewardName() { return stewardName; }
    public String getStewardEmail() { return stewardEmail; }
    public void setStewardName(String stewardName) { this.stewardName = stewardName; }
    public void setStewardEmail(String stewardEmail) { this.stewardEmail = stewardEmail; }
}
