package com.dyzwj.test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class DateTest {

    public static void main(String[] args) {

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String format = .formdateTimeFormatterat(LocalDate.now());
        System.out.println(format);

    }
}
