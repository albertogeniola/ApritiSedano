#ifndef LOGGER_H
#define LOGGER_H

#include <Arduino.h>

class Logger {
public:
    static void init();
    static void logOperation(const char* operation);
    static void printLogs();
    static void clearLogs();
    static void printSysInfo();
};

#endif // LOGGER_H
