package Utils;

import Backend.DownloadFolderLocator;
import Enums.OS;
import Enums.Program;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.buildobjects.process.ProcBuilder;
import org.hildan.fxgson.FxGson;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static Enums.Program.YT_DLP;
import static Utils.DriftyConstants.*;

public final class Utility {
    private static final Random random = new Random(System.currentTimeMillis());
    private static final MessageBroker M = Environment.getMessageBroker();
    private static final Scanner SC = ScannerFactory.getInstance();
    private static boolean interrupted;


    public static void setStartTime() {
    }

    public Utility() {
    }

    public static boolean isYoutube(String url) {
        String pattern = "^(http(s)?://)?((w){3}.)?youtu(be|.be)?(\\.com)?/.+";
        return url.matches(pattern);
    }

    public static boolean isInstagram(String url) {
        String pattern = "(https?://(?:www\\.)?instagr(am|.am)?(\\.com)?/(p|reel)/([^/?#&]+)).*";
        return url.matches(pattern);
    }

    public static boolean isExtractableLink(String link) {
        return isYoutube(link) || isInstagram(link);
    }

    public static boolean isLinkValid(String link) {
        try {
            URL url = URI.create(link).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD"); // Faster validation and hence improves performance
            connection.connect();
            M.msgLinkInfo("Link is valid!");
            return true;
        } catch (ConnectException e) {
            M.msgLinkError("Connection to the link timed out! Please check your internet connection. " + e.getMessage());
        } catch (UnknownHostException unknownHost) {
            try {
                URL projectWebsite = URI.create(DRIFTY_WEBSITE_URL).toURL();
                HttpURLConnection connectProjectWebsite = (HttpURLConnection) projectWebsite.openConnection();
                connectProjectWebsite.connect();
                M.msgLinkError("Link is invalid!"); // If our project website can be connected to, then the one entered by user is not valid! [NOTE: UnknownHostException is thrown if either internet is not connected or the website address is incorrect]
            } catch (UnknownHostException e) {
                M.msgLinkError("You are not connected to the Internet!");
            } catch (MalformedURLException e) {
                M.msgLinkError("The link is not correctly formatted! " + e.getMessage());
            } catch (IOException e) {
                M.msgLinkError("Failed to connect to the project website! " + e.getMessage());
            }
        } catch (ProtocolException e) {
            M.msgLinkError("An error occurred with the protocol! " + e.getMessage());
        } catch (MalformedURLException e) {
            M.msgLinkError("The link is not correctly formatted! " + e.getMessage());
        } catch (IOException e) {
            M.msgLinkError("Failed to connect to " + link + " ! " + e.getMessage());
        } catch (IllegalArgumentException e) {
            M.msgLinkError(link + " is not a URL; error: " + e.getMessage());
        }
        return false;
    }

    public static String findFilenameInLink(String link) {
        String filename = "";
        if (isInstagram(link) || isYoutube(link)) {
            LinkedList<String> linkMetadataList = Utility.getLinkMetadata(link);
            for (String json : Objects.requireNonNull(linkMetadataList)) {
                filename = Utility.getFilenameFromJson(json);
            }
        } else {
            // Example: "example.com/file.txt" prints "Filename detected: file.txt"
            // example.com/file.json -> file.json
            String file = link.substring(link.lastIndexOf("/") + 1);
            if (file.isEmpty()) {
                M.msgFilenameError(AUTO_FILE_NAME_DETECTION_FAILED);
                return null;
            }
            int index = file.lastIndexOf(".");
            if (index < 0) {
                M.msgFilenameError(AUTO_FILE_NAME_DETECTION_FAILED);
                return null;
            }
            String extension = file.substring(index);
            // edge case 1: "example.com/."
            if (extension.length() == 1) {
                M.msgFilenameError(AUTO_FILE_NAME_DETECTION_FAILED);
                return null;
            }
            // file.png?width=200 -> file.png
            filename = file.split("([?])")[0];
            M.msgFilenameInfo(FILENAME_DETECTED + "\"" + filename + "\"");
        }
        return filename;
    }

    public static String getHomeDownloadFolder() {
        String downloadsFolder;
        M.msgDirInfo(TRYING_TO_AUTO_DETECT_DOWNLOADS_FOLDER);
        if (!OS.isWindows()) {
            String home = System.getProperty(USER_HOME_PROPERTY);
            downloadsFolder = home + DOWNLOADS_FILE_PATH;
        } else {
            downloadsFolder = DownloadFolderLocator.findPath() + System.getProperty("file.separator");
        }
        if (downloadsFolder.equals(System.getProperty("file.separator"))) {
            M.msgDirError(FAILED_TO_RETRIEVE_DEFAULT_DOWNLOAD_FOLDER);
        } else {
            M.msgDirInfo(DEFAULT_DOWNLOAD_FOLDER + downloadsFolder);
        }
        return downloadsFolder;
    }

    public boolean yesNoValidation(String input, String printMessage) {
        while (input.isEmpty()) {
            System.out.println(ENTER_Y_OR_N);
            M.msgLogError(ENTER_Y_OR_N);
            System.out.print(printMessage);
            input = SC.nextLine().toLowerCase();
        }

        char choice = input.charAt(0);
        if (choice == 'y') {
            return true;
        } else if (choice == 'n') {
            return false;
        } else {
            System.out.println("Invalid input!");
            M.msgLogError("Invalid input!");
            System.out.print(printMessage);
            input = SC.nextLine().toLowerCase();
            yesNoValidation(input, printMessage);
        }
        return false;
    }

    public static LinkedList<String> getLinkMetadata(String link) {
        try {
            LinkedList<String> list = new LinkedList<>();
            File driftyJsonFolder = Program.getJsonDataPath().toFile();
            if (driftyJsonFolder.exists() && driftyJsonFolder.isDirectory()) {
                FileUtils.forceDelete(driftyJsonFolder); // Deletes the previously generated temporary directory for Drifty
            }
            if (!driftyJsonFolder.mkdir()) {
                M.msgLinkError("Failed to create temporary directory for Drifty to get link metadata!");
                return null;
            }
            Thread linkThread = new Thread(ytDLPJsonData(driftyJsonFolder.getAbsolutePath(), link));
            linkThread.start();
            while (!linkThread.getState().equals(Thread.State.TERMINATED) && !linkThread.isInterrupted()) {
                sleep(100);
                interrupted = linkThread.isInterrupted();
            }

            if (interrupted) {
                FileUtils.forceDelete(driftyJsonFolder);
                return null;
            }

            File[] files = driftyJsonFolder.listFiles();
            if (files != null) {
                for (File file : files) {
                    String ext = FilenameUtils.getExtension(file.getAbsolutePath());
                    if (ext.toLowerCase().contains("json")) {
                        String linkMetadata = FileUtils.readFileToString(file, Charset.defaultCharset());
                        list.addLast(linkMetadata);
                    }

                }
                FileUtils.forceDelete(driftyJsonFolder); // delete the metadata files of Drifty from the config directory
            }
            return list;
        } catch (IOException e) {
            M.msgLinkError("Failed to perform I/O operations on link metadata! " + e.getMessage());
            return null;
        }
    }

    public static String getURLFromJson(String jsonString) {
        String json = makePretty(jsonString);
        String regexLink = "(\"webpage_url\": \")(.+)(\")";
        String extractedUrl = "";
        Pattern p = Pattern.compile(regexLink);
        Matcher m = p.matcher(json);
        if (m.find()) {
            extractedUrl = StringEscapeUtils.unescapeJava(m.group(2));
        }
        return extractedUrl;
    }

    public static String makePretty(String json) {
        // The regex strings won't match unless the json string is converted to pretty format
        GsonBuilder g = new GsonBuilder();
        Gson gson = FxGson.addFxSupport(g).setPrettyPrinting().create();
        JsonElement element = JsonParser.parseString(json);
        return gson.toJson(element);
    }

    public static String getFilenameFromJson(String jsonString) {
        String json = makePretty(jsonString);
        String filename;
        String regexFilename = "(\"title\": \")(.+)(\",)";
        Pattern p = Pattern.compile(regexFilename);
        Matcher m = p.matcher(json);
        if (m.find()) {
            filename = cleanFilename(m.group(2)) + ".mp4";
            M.msgFilenameInfo(FILENAME_DETECTED + "\"" + filename + "\"");
        } else {
            filename = cleanFilename("Unknown_Filename_") + randomString(15) + ".mp4";
            M.msgFilenameError(AUTO_FILE_NAME_DETECTION_FAILED_YT_IG);
        }
        return filename;
    }

    public static String cleanFilename(String filename) {
        String fn = StringEscapeUtils.unescapeJava(filename);
        return fn.replaceAll("[^a-zA-Z0-9-._)<(> ]+", "");
    }

    private static Runnable ytDLPJsonData(String folderPath, String link) {
        return () -> {
            String command = Program.get(YT_DLP);
            String[] args = new String[]{"--write-info-json", "--skip-download", "--restrict-filenames", "-P", folderPath, link};
            new ProcBuilder(command)
                    .withArgs(args)
                    .withErrorStream(System.err)
                    .withNoTimeout()
                    .run();
        };
    }

    public static boolean isURL(String text) {
        String regex = "^(http(s)?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        return m.matches();
    }

    public static void sleep(long time) {
        try {
            TimeUnit.MILLISECONDS.sleep(time);
        } catch (InterruptedException e) {
            M.msgLinkError("The calling method failed to sleep for " + time + " milliseconds. It got interrupted. " + e.getMessage());
        }
    }

    public static String randomString(int characterCount) {
        String source = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";
        int count = source.length();
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < characterCount; x++) {
            int index = random.nextInt(count);
            sb.append(source.charAt(index));
        }
        return sb.toString();
    }
}
