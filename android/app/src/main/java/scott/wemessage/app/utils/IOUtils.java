/*
 *  weMessage - iMessage for Android
 *  Copyright (C) 2018 Roman Scott
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package scott.wemessage.app.utils;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.annotation.AnyRes;
import android.support.annotation.NonNull;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

import scott.wemessage.R;
import scott.wemessage.app.AppLogger;
import scott.wemessage.app.models.chats.Chat;
import scott.wemessage.app.models.chats.GroupChat;
import scott.wemessage.app.models.chats.PeerChat;
import scott.wemessage.app.models.sms.chats.SmsChat;
import scott.wemessage.app.models.users.Contact;
import scott.wemessage.app.models.users.Handle;
import scott.wemessage.app.utils.media.MediaDownloadCallbacks;
import scott.wemessage.app.utils.view.DisplayUtils;
import scott.wemessage.app.weMessage;
import scott.wemessage.commons.types.MimeType;
import scott.wemessage.commons.utils.FileUtils;
import scott.wemessage.commons.utils.StringUtils;

public class IOUtils {

    public static Uri getUriFromResource(@NonNull Context context, @AnyRes int resId) throws Resources.NotFoundException {
        Resources res = context.getResources();
        Uri resUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + res.getResourcePackageName(resId) + '/' + res.getResourceTypeName(resId) + '/' + res.getResourceEntryName(resId));

        return resUri;
    }

    public static Uri getUriFromFile(File file){
        return Uri.fromFile(file);
    }

    public static String getChatIconUri(Chat chat, IconSize iconSize){
        if (chat.getChatType() == Chat.ChatType.PEER){
            PeerChat peerChat = (PeerChat) chat;

            Contact contact = weMessage.get().getMessageDatabase().getContactByHandle(peerChat.getHandle());

            if (contact == null || contact.getContactPictureFileLocation() == null){
                return getDefaultContactUri(iconSize, chat instanceof SmsChat);
            }else if (StringUtils.isEmpty(contact.getContactPictureFileLocation().getFileLocation())){
                return getDefaultContactUri(iconSize, chat instanceof SmsChat);
            }else {
                return Uri.fromFile(contact.getContactPictureFileLocation().getFile()).toString();
            }
        }else {
            GroupChat groupChat = (GroupChat) chat;

            if (groupChat.getChatPictureFileLocation() == null){
                return getDefaultChatUri(iconSize, chat instanceof SmsChat);
            } else if (StringUtils.isEmpty(groupChat.getChatPictureFileLocation().getFileLocation())){
                return getDefaultChatUri(iconSize, chat instanceof SmsChat);
            } else {
                return Uri.fromFile(groupChat.getChatPictureFileLocation().getFile()).toString();
            }
        }
    }

    public static String getContactIconUri(Handle handle, IconSize iconSize){
        try {
            Contact contact = weMessage.get().getMessageDatabase().getContactByHandle(handle);

            if (contact == null || contact.getContactPictureFileLocation() == null) {
                return getDefaultContactUri(iconSize, handle.getHandleType() == Handle.HandleType.SMS);
            } else if (StringUtils.isEmpty(contact.getContactPictureFileLocation().getFileLocation())) {
                return getDefaultContactUri(iconSize, handle.getHandleType() == Handle.HandleType.SMS);
            } else {
                return Uri.fromFile(contact.getContactPictureFileLocation().getFile()).toString();
            }
        }catch (Exception ex){
            return getDefaultContactUri(iconSize, handle != null && handle.getHandleType() == Handle.HandleType.SMS);
        }
    }

    public static String getDefaultContactUri(IconSize iconSize, boolean isSms){
        int resId;

        if (isSms){
            if (iconSize == IconSize.NORMAL) {
                resId = R.drawable.ic_default_contact_sms;
            } else if (iconSize == IconSize.LARGE) {
                resId = R.drawable.ic_default_contact_large_sms;
            } else {
                resId = R.drawable.ic_default_contact_sms;
            }
        }else {
            if (iconSize == IconSize.NORMAL) {
                resId = R.drawable.ic_default_contact;
            } else if (iconSize == IconSize.LARGE) {
                resId = R.drawable.ic_default_contact_large;
            } else {
                resId = R.drawable.ic_default_contact;
            }
        }

        return IOUtils.getUriFromResource(weMessage.get(), resId).toString();
    }

    public static String getDefaultChatUri(IconSize iconSize, boolean isSms){
        int resId;

        if (isSms){
            if (iconSize == IconSize.NORMAL) {
                resId = R.drawable.ic_default_group_chat_sms;
            } else if (iconSize == IconSize.LARGE) {
                resId = R.drawable.ic_default_group_chat_large_sms;
            } else {
                resId = R.drawable.ic_default_group_chat_sms;
            }
        }else {
            if (iconSize == IconSize.NORMAL) {
                resId = R.drawable.ic_default_group_chat;
            } else if (iconSize == IconSize.LARGE) {
                resId = R.drawable.ic_default_group_chat_large;
            } else {
                resId = R.drawable.ic_default_group_chat;
            }
        }

        return IOUtils.getUriFromResource(weMessage.get(), resId).toString();
    }

    public static void saveMediaToGallery(final MediaDownloadCallbacks callbacks, final Activity activity, final View view, MimeType mimeType, String attachmentUri){
        if (!callbacks.canMediaDownloadTaskStart(attachmentUri)) return;

        callbacks.onMediaDownloadTaskStart(attachmentUri);

        if (mimeType == MimeType.IMAGE){
            new AsyncTask<String, Void, GallerySaveResult>() {

                @Override
                protected GallerySaveResult doInBackground(String... params) {
                    try {
                        File original = new File(Uri.parse(params[0]).getPath());
                        File output = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), original.getName());

                        if (!output.exists()){
                            FileUtils.copy(original, output);

                            ContentValues values = new ContentValues();
                            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
                            values.put(MediaStore.Images.Media.MIME_TYPE, AndroidUtils.getMimeTypeStringFromPath(output.getAbsolutePath()));
                            values.put(MediaStore.MediaColumns.DATA, output.getAbsolutePath());

                            activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                            return GallerySaveResult.SUCCESS;
                        }else {
                            return GallerySaveResult.ALREADY_EXISTS;
                        }
                    }catch (Exception ex){
                        AppLogger.error("An error occurred while saving an image to gallery", ex);
                        return GallerySaveResult.ERROR;
                    }finally {
                        callbacks.onMediaDownloadTaskFinish(params[0]);
                    }
                }

                @Override
                protected void onPostExecute(GallerySaveResult result) {
                    if (activity != null && !activity.isDestroyed() && !activity.isFinishing() && view != null) {
                        switch (result){
                            case SUCCESS:
                                DisplayUtils.showPositiveSnackbar(activity, view, 5, activity.getString(R.string.image_gallery_save));
                                break;
                            case ALREADY_EXISTS:
                                DisplayUtils.showErroredSnackbar(activity, view, 5, activity.getString(R.string.image_gallery_exists));
                                break;
                            case ERROR:
                                DisplayUtils.showErroredSnackbar(activity, view, 5, activity.getString(R.string.image_gallery_error));
                                break;
                            default:
                                break;
                        }
                    }
                }
            }.execute(attachmentUri);
        }else if (mimeType == MimeType.VIDEO){
            new AsyncTask<String, Void, GallerySaveResult>() {

                @Override
                protected GallerySaveResult doInBackground(String... params) {
                    try {
                        File original = new File(Uri.parse(params[0]).getPath());
                        File output = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), original.getName());

                        if (!output.exists()){
                            FileUtils.copy(original, output);

                            ContentValues values = new ContentValues();
                            values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
                            values.put(MediaStore.Video.Media.MIME_TYPE, AndroidUtils.getMimeTypeStringFromPath(output.getAbsolutePath()));
                            values.put(MediaStore.MediaColumns.DATA, output.getAbsolutePath());

                            activity.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                            return GallerySaveResult.SUCCESS;
                        }else {
                            return GallerySaveResult.ALREADY_EXISTS;
                        }
                    }catch (Exception ex){
                        AppLogger.error("An error occurred while saving a video to gallery", ex);
                        return GallerySaveResult.ERROR;
                    }finally {
                        callbacks.onMediaDownloadTaskFinish(params[0]);
                    }
                }

                @Override
                protected void onPostExecute(GallerySaveResult result) {
                    if (activity != null && !activity.isDestroyed() && !activity.isFinishing() && view != null) {
                        switch (result){
                            case SUCCESS:
                                DisplayUtils.showPositiveSnackbar(activity, view, 5, activity.getString(R.string.video_gallery_save));
                                break;
                            case ALREADY_EXISTS:
                                DisplayUtils.showErroredSnackbar(activity, view, 5, activity.getString(R.string.video_gallery_exists));
                                break;
                            case ERROR:
                                DisplayUtils.showErroredSnackbar(activity, view, 5, activity.getString(R.string.video_gallery_error));
                                break;
                            default:
                                break;
                        }
                    }
                }
            }.execute(attachmentUri);
        }
    }

    public static void setDeviceName(){
        new AsyncTask<Void, Void, Void>(){
            @Override
            protected Void doInBackground(Void... voids) {
                String deviceInfo = weMessage.get().getSharedPreferences().getString(weMessage.SHARED_PREFERENCES_DEVICE_INFO, "");

                if (!StringUtils.isEmpty(deviceInfo)) {
                    Device device = Device.fromString(deviceInfo);

                    if (device != null && device.getManufacturer().equalsIgnoreCase(Build.MANUFACTURER) && device.getModel().equalsIgnoreCase(Build.MODEL)) return null;
                }

                try {
                    fetchDeviceData("https://storage.googleapis.com/play_public/supported_devices.csv");
                }catch (UnknownHostException | MalformedURLException | SSLException | SocketException exc){
                    try {
                        fetchDeviceData("http://storage.googleapis.com/play_public/supported_devices.csv");
                    }catch (UnknownHostException | MalformedURLException ex) {

                    }catch (Exception ex){
                        AppLogger.error("An error occurred while fetching the device's name", ex);
                    }
                }catch (Exception ex){
                    AppLogger.error("An error occurred while fetching the device's name", ex);
                }
                return null;
            }
        }.execute();
    }

    private static void fetchDeviceData(String urlString) throws IOException {
        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-16"))) {
            reader.readLine();
            String line;

            while ((line = reader.readLine()) != null) {
                String[] records = line.split(",");
                if (records.length == 4) {
                    String manufacturer = records[0];
                    String name = records[1];
                    String model = records[3];

                    if (manufacturer.equalsIgnoreCase(Build.MANUFACTURER) && model.equalsIgnoreCase(Build.MODEL)) {
                        Device device = new Device(manufacturer, name, model);
                        SharedPreferences.Editor editor = weMessage.get().getSharedPreferences().edit();

                        editor.putString(weMessage.SHARED_PREFERENCES_DEVICE_INFO, device.parse());
                        editor.apply();
                        break;
                    }
                }
            }
        }
    }

    private enum GallerySaveResult {
        SUCCESS,
        ALREADY_EXISTS,
        ERROR
    }

    public enum IconSize {
        NORMAL,
        LARGE
    }
}