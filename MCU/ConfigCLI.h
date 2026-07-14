#ifndef CONFIG_CLI_H
#define CONFIG_CLI_H

#include <Arduino.h>

class ConfigCLI {
public:
    static void init();
    static void handle();
    static void loadSecret();
private:
    static void showMenu();
    static void syncNTP();
    static void setSecret();
    static String readString();
};

#endif
