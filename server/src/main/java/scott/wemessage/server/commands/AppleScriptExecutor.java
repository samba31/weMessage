package scott.wemessage.server.commands;

import scott.wemessage.commons.types.ActionType;
import scott.wemessage.commons.types.ReturnType;
import scott.wemessage.server.MessageServer;
import scott.wemessage.server.configuration.ServerConfiguration;
import scott.wemessage.server.ServerLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

public final class AppleScriptExecutor extends Thread {

    private final String TAG = "AppleScript Runner";
    private final List<UUID> queue = Collections.synchronizedList(new ArrayList<UUID>());
    private final Object scriptsFolderLock = new Object();
    private final Object scriptsFolderPathLock = new Object();
    private final Object tempFolderLock = new Object();

    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private MessageServer messageServer;
    private String scriptsFolderPath;
    private File scriptsFolder;
    private Path tempFolder;

    public AppleScriptExecutor(MessageServer server, ServerConfiguration serverConfiguration) {
        this.messageServer = server;

        synchronized (scriptsFolderPathLock) {
            this.scriptsFolderPath = serverConfiguration.getParentDirectoryPath() + "/scripts";
        }
        synchronized (scriptsFolderLock) {
            this.scriptsFolder = new File(getScriptsFolderPath());
        }
        try {
            synchronized (tempFolderLock) {
                this.tempFolder = Files.createTempDirectory("weServer");
            }
        }catch(IOException ex){
            ServerLogger.error(TAG, "An unknown error occurred while creating the weServer temp directory. Shutting down!", ex);
            messageServer.shutdown(-1, false);
        }

        if (!getScriptsFolder().exists()){
            ServerLogger.error(TAG, "weMessage Scripts folder could not be found. Shutting down!", new Exception());
            messageServer.shutdown(-1, false);
        }
    }

    public Path getTempFolder(){
        synchronized (tempFolderLock){
            return tempFolder;
        }
    }

    public String getScriptsFolderPath(){
        synchronized (scriptsFolderPathLock) {
            return scriptsFolderPath;
        }
    }

    public File getScriptsFolder(){
        synchronized (scriptsFolderLock){
            return scriptsFolder;
        }
    }

    public void run(){
        isRunning.set(true);
    }

    public void stopService(){
        if (isRunning.get()){
            isRunning.set(false);
            try {
                Files.walkFileTree(getTempFolder(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                        if (e == null) {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                        throw e;
                    }
                });
            } catch (IOException e) {
                ServerLogger.error(TAG, "Failed to delete temp folder" + getTempFolder().toString(), e);
            }
        }
    }

    public Object runScript(ActionType actionType, String[] args){
        File scriptFile;
        try {
            File[] scriptFiles = getScriptsFolder().listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith(actionType.getScriptName());
                }
            });
            scriptFile = scriptFiles[0];
        }catch(Exception ex){
            ServerLogger.error(TAG, "The script " + actionType.getScriptName() + ".scpt does not exist!", new NullPointerException());
            return null;
        }

        UUID uuid = UUID.randomUUID();
        AtomicBoolean shouldLoop = new AtomicBoolean(true);
        queue.add(uuid);

        while (shouldLoop.get()){
            if (queue.get(0).equals(uuid)){
                queue.remove(uuid);
                shouldLoop.set(false);
            }
        }

        Object result;
        String returnCode = null;
        Process process;

        try {
            startMessagesApp();
            switch (actionType) {
                case SEND_MESSAGE:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2]).start();
                    break;
                case SEND_GROUP_MESSAGE:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2], args[3], args[4]).start();
                    break;
                case RENAME_GROUP:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2], args[3]).start();
                    break;
                case ADD_PARTICIPANT:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2], args[3]).start();
                    break;
                case REMOVE_PARTICIPANT:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2], args[3]).start();
                    break;
                case CREATE_GROUP:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2]).start();
                    break;
                case LEAVE_GROUP:
                    process = new ProcessBuilder("osascript", scriptFile.getAbsolutePath(), args[0], args[1], args[2]).start();
                    break;
                default:
                    returnCode = null;
                    process = null;
                    break;
            }

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = bufferedReader.readLine()) != null){
                returnCode = line;
            }
            bufferedReader.close();

            List<String> resultReturns = Arrays.asList(returnCode.split(", "));
            List<ReturnType> resultReturnsList = new ArrayList<>();

            for (String s : resultReturns) {
                resultReturnsList.add(ReturnType.fromCode(Integer.parseInt(s)));
            }

            if(resultReturnsList.isEmpty()){
                result = null;
            }else if (resultReturnsList.size() == 1){
                ReturnType returnType = resultReturnsList.get(0);
                result = returnType;

                if (returnType == ReturnType.UI_ERROR){
                    ServerLogger.log(ServerLogger.Level.ERROR, TAG, "A UI error occurred within the Messages App.");
                    ServerLogger.log(ServerLogger.Level.ERROR, TAG, "In order to prevent further errors from occurring, weMessage will force close and relaunch it.");
                    killMessagesApp(true);
                }
            }else {
                result = resultReturnsList;

                for (ReturnType returnType : resultReturnsList) {
                    if (returnType == ReturnType.UI_ERROR) {
                        ServerLogger.log(ServerLogger.Level.ERROR, TAG, "A UI error occurred within the Messages App.");
                        ServerLogger.log(ServerLogger.Level.ERROR, TAG, "In order to prevent further errors from occurring, weMessage will force close and relaunch it.");
                        killMessagesApp(true);
                        break;
                    }
                }
            }
        }catch (Exception ex){
            ServerLogger.error(TAG, "An error occurred while running script " + actionType.getScriptName() + ".scpt", ex);
            result = null;
        }
        return result;
    }

    public boolean isSetup(){
        File setupScriptFile;

        try {
            File[] scriptFiles = getScriptsFolder().listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith("Setup");
                }
            });
            setupScriptFile = scriptFiles[0];
        }catch(Exception ex){
            ServerLogger.error(TAG, "The script Setup.scpt does not exist!", new NullPointerException());
            return false;
        }

        try {
            Process process = new ProcessBuilder("osascript", setupScriptFile.getAbsolutePath()).start();

            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            int returnCode = -1;

            while ((line = bufferedReader.readLine()) != null){
                returnCode = Integer.parseInt(line);
            }
            bufferedReader.close();

            if(returnCode == ReturnType.ACTION_PERFORMED.getCode()){
                return true;
            }else {
                return false;
            }
        }catch(Exception ex){
            ServerLogger.error("An error occurred while checking to see if weMessage is configured to start.", ex);
            return false;
        }
    }

    public void startMessagesApp() throws ScriptException {
        String startScript = "on run\n" +
                "\tif isAppRunning(\"Messages\") is not equal to true then\n" +
                "\t\ttell application \"Messages\" to activate\n" +
                "\tend if\n" +
                "end run\n" +
                "\n" +
                "on isAppRunning(targetApp)\n" +
                "\ttell application \"System Events\"\n" +
                "\t\tset processExists to exists process targetApp\n" +
                "\tend tell\n" +
                "end isAppRunning";
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("AppleScriptEngine");
        engine.eval(startScript);
    }

    public void killMessagesApp(boolean respring) throws ScriptException {
        String killScript = "on run\n" +
                "\tif isAppRunning(\"Messages\") then\n" +
                "\t\ttell application \"Messages\" to quit\n" +
                "\tend if\n" +
                "end run\n" +
                "\n" +
                "on isAppRunning(targetApp)\n" +
                "\ttell application \"System Events\"\n" +
                "\t\tset processExists to exists process targetApp\n" +
                "\tend tell\n" +
                "end isAppRunning";
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("AppleScriptEngine");
        engine.eval(killScript);

        if (respring){
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        startMessagesApp();
                    }catch(ScriptException ex){
                        ServerLogger.error(TAG, "An error occurred while executing the script to restart Messages.", ex);
                    }
                }
            }, 200);
        }
    }
}