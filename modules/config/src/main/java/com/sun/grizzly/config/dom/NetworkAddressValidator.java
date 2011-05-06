package com.sun.grizzly.config.dom;

import java.net.InetAddress;
import java.net.UnknownHostException;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class NetworkAddressValidator implements ConstraintValidator<NetworkAddress, String> {
    public void initialize(final NetworkAddress networkAddress) {
        System.out.println("NetworkAddressValidator.initialize");
    }

    public boolean isValid(final String s, final ConstraintValidatorContext constraintValidatorContext) {
        System.out.println("NetworkAddressValidator.isValid");
        try {
            InetAddress.getByName(s);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
