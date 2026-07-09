package com.originex.customer.domain.model;

import java.util.UUID;

/**
 * Address value object within Customer aggregate.
 */
public class Address {

    private UUID addressId;
    private AddressType type;
    private String line1;
    private String line2;
    private String city;
    private String state;
    private String pincode;
    private String country;

    public static Address create(AddressType type, String line1, String line2,
                                 String city, String state, String pincode) {
        Address addr = new Address();
        addr.addressId = UUID.randomUUID();
        addr.type = type;
        addr.line1 = line1;
        addr.line2 = line2;
        addr.city = city;
        addr.state = state;
        addr.pincode = pincode;
        addr.country = "IN";
        return addr;
    }

    public UUID getAddressId() { return addressId; }
    public AddressType getType() { return type; }
    public String getLine1() { return line1; }
    public String getLine2() { return line2; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPincode() { return pincode; }
    public String getCountry() { return country; }

    public Address() {}

    public enum AddressType { PERMANENT, CURRENT, OFFICE }
}
