package com.playcode.app;
public record Question(long id, String subject, String topic, String difficulty, String question,
                       String optionA, String optionB, String optionC, String optionD, String correctAnswer) {}
