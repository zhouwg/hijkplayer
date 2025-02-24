 /*
  * Copyright (c) Project KanTV. 2021-2023. All rights reserved.
  *
  * Copyright (c) 2024- KanTV Authors. All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *      http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

 package com.cdeos.kantv.ui.fragment;

 import android.annotation.SuppressLint;
 import android.app.Activity;
 import android.content.Context;
 import android.content.Intent;
 import android.content.res.Resources;
 import android.net.Uri;
 import android.os.Environment;
 import android.view.View;
 import android.widget.AdapterView;
 import android.widget.BaseAdapter;
 import android.widget.GridView;
 import android.widget.Toast;

 import androidx.annotation.NonNull;
 import androidx.appcompat.app.AppCompatActivity;

 import com.cdeos.kantv.mvp.impl.TVGridPresenterImpl;
 import com.cdeos.kantv.mvp.presenter.TVGridPresenter;
 import com.cdeos.kantv.mvp.view.TVGridView;
 import com.cdeos.kantv.R;
 import com.cdeos.kantv.base.BaseMvpFragment;
 import com.cdeos.kantv.base.BaseRvAdapter;
 import com.cdeos.kantv.bean.FolderBean;
 import com.cdeos.kantv.bean.event.SendMsgEvent;
 import com.cdeos.kantv.player.common.utils.Constants;;
 import com.cdeos.kantv.ui.activities.ShellActivity;
 import com.cdeos.kantv.ui.activities.play.PlayerManagerActivity;
 import com.cdeos.kantv.utils.AppConfig;
 import com.cdeos.kantv.utils.Settings;

 import org.greenrobot.eventbus.EventBus;
 import org.greenrobot.eventbus.*;

 import java.io.File;
 import java.io.FileInputStream;
 import java.io.FileNotFoundException;
 import java.io.IOException;
 import java.lang.reflect.Field;
 import java.util.ArrayList;
 import java.util.Arrays;
 import java.util.List;

 import butterknife.BindView;
 import cdeos.media.player.KANTVJNIDecryptBuffer;
 import cdeos.media.player.KANTVMediaGridAdapter;
 import cdeos.media.player.KANTVMediaGridItem;
 import io.reactivex.disposables.Disposable;
 import cdeos.media.player.KANTVDRM;
 import cdeos.media.player.CDEAssetLoader;
 import cdeos.media.player.CDEContentDescriptor;
 import cdeos.media.player.CDELog;
 import cdeos.media.player.CDEMediaType;
 import cdeos.media.player.CDEUtils;
 import cdeos.media.player.KANTVDRMManager;


 public class TVGridFragment extends BaseMvpFragment<TVGridPresenter> implements TVGridView {
     private static final String TAG = TVGridFragment.class.getName();

     @BindView(R.id.tv_grid_layout)
     GridView layout;

     private CDEMediaType mMediaType = CDEMediaType.MEDIA_TV;
     private final static KANTVDRM mKANTVDRM = KANTVDRM.getInstance();
     private boolean mEPGDataLoadded = false;

     private Context mContext;
     private Activity mActivity;
     private Settings mSettings;

     private BaseAdapter mAdapter = null;
     private ArrayList<KANTVMediaGridItem> mData = new ArrayList<KANTVMediaGridItem>();
     private List<CDEContentDescriptor> mContentList = new ArrayList<CDEContentDescriptor>();

     private int gridItemImageID = 0;
     private int gridItemTextID = 0;

     public static TVGridFragment newInstance() {
         return new TVGridFragment();
     }

     @NonNull
     @Override
     protected TVGridPresenter initPresenter() {
         return new TVGridPresenterImpl(this, this);
     }

     @Override
     protected int initPageLayoutId() {
         return R.layout.fragment_tvgrid;
     }

     @SuppressLint("CheckResult")
     @Override
     public void initView() {
         long beginTime = 0;
         long endTime = 0;
         beginTime = System.currentTimeMillis();

         mActivity = getActivity();
         mContext = mActivity.getBaseContext();
         mSettings = new Settings(mContext);
         mSettings.updateUILang((AppCompatActivity) mActivity);

         loadEPGData();
         mEPGDataLoadded = true;
         layoutUI(mActivity);

         endTime = System.currentTimeMillis();
         CDELog.d(TAG, "initView cost: " + (endTime - beginTime) + " milliseconds");
     }

     @Override
     public void initListener() {
     }


     @Override
     public void onDestroy() {
         super.onDestroy();
         EventBus.getDefault().unregister(this);
     }


     @Override
     public void onResume() {
         super.onResume();
         if (mEPGDataLoadded) {
             CDELog.d(TAG, "epg data already loadded");
         } else {
             CDELog.d(TAG, "epg data not loadded");
             loadEPGData();
             mEPGDataLoadded = true;
             layoutUI(mActivity);
         }
     }


     @Override
     public void onStop() {
         super.onStop();
         mEPGDataLoadded = false;
     }


     private void initPlayback() {
         if (AppConfig.getInstance().getPlayerType() == Constants.PLAYERENGINE__FFmpeg) {
             KANTVDRM mKANTVDRM = KANTVDRM.getInstance();
             mKANTVDRM.ANDROID_JNI_Init(mActivity.getBaseContext(), CDEAssetLoader.getDataPath(mActivity.getBaseContext()));
             mKANTVDRM.ANDROID_JNI_SetLocalEMS(CDEUtils.getLocalEMS());
         }
         if (AppConfig.getInstance().getPlayerType() == Constants.PLAYERENGINE__Exoplayer) {
             if (KANTVDRMManager.openPlaybackService() != 0) {
                 CDELog.e(TAG, "init drm failed");
                 return;
             }
             mKANTVDRM.ANDROID_JNI_SetLocalEMS(CDEUtils.getLocalEMS());
             KANTVDRMManager.setMultiDrmInfo(CDEUtils.getDrmScheme());
             CDELog.d(TAG, "after calling ServiceManager.openPlaybackService ");
         }
     }


     //TODO:merge codes of local epg info from remote file or local file
     private void loadEPGData() {
         long beginTime = 0;
         long endTime = 0;
         beginTime = System.currentTimeMillis();
         CDELog.d(TAG, "epg updated status:" + CDEUtils.getEPGUpdatedStatus(mMediaType));
         mContentList.clear();
         /* 2025-01-29, remove encrypted kantv.bin
         String encryptedEPGFileName = "kantv.bin";
         KANTVJNIDecryptBuffer dataEntity = KANTVJNIDecryptBuffer.getInstance();
         KANTVDRM mKANTVDRM = KANTVDRM.getInstance();
         byte[] realPayload = null;
         boolean usingRemoteEPG = false;
         File xmlRemoteFile = new File(CDEUtils.getDataPath(mContext), "kantv.bin");
         int nPaddingLen = 0;

         mData.clear();
         mContentList.clear();

         if (xmlRemoteFile.exists()) {
             CDELog.d(TAG, "new remote epg file exist: " + xmlRemoteFile.getAbsolutePath());
             usingRemoteEPG = true;
             String xmlLoaddedFileName = xmlRemoteFile.getAbsolutePath();
             CDELog.d(TAG, "load epg from file: " + xmlLoaddedFileName + " which download from Master EPG server:" + CDEUtils.getKANTVMasterServer());

             {
                 beginTime = System.currentTimeMillis();
                 File file = new File(xmlLoaddedFileName);
                 int fileLength = (int) file.length();
                 CDELog.d(TAG, "encrypted file len:" + fileLength);

                 try {

                     byte[] fileContent = new byte[fileLength];
                     FileInputStream in = new FileInputStream(file);
                     in.read(fileContent);
                     in.close();
                     CDELog.d(TAG, "encrypted file length:" + fileLength);
                     if (fileLength < 2) {
                         CDELog.d(TAG, "shouldn't happen, pls check encrypted epg data");
                         return;
                     }
                     byte[] paddingInfo = new byte[2];
                     paddingInfo[0] = 0;
                     paddingInfo[1] = 0;
                     System.arraycopy(fileContent, 0, paddingInfo, 0, 2);
                     CDELog.d(TAG, "padding len:" + Arrays.toString(paddingInfo));
                     CDELog.d(TAG, "padding len:" + (int) paddingInfo[0]);
                     CDELog.d(TAG, "padding len:" + (int) paddingInfo[1]);
                     nPaddingLen = (((paddingInfo[0] & 0xFF) << 8) | (paddingInfo[1] & 0xFF));
                     CDELog.d(TAG, "padding len:" + nPaddingLen);

                     byte[] payload = new byte[fileLength - 2];
                     System.arraycopy(fileContent, 2, payload, 0, fileLength - 2);


                     CDELog.d(TAG, "encrypted file length:" + fileLength);
                     mKANTVDRM.ANDROID_JNI_Decrypt(payload, fileLength - 2, dataEntity, KANTVJNIDecryptBuffer.getDirectBuffer());
                     CDELog.d(TAG, "decrypted data length:" + dataEntity.getDataLength());
                     if (dataEntity.getData() != null) {
                         CDELog.d(TAG, "decrypted ok");
                     } else {
                         CDELog.d(TAG, "decrypted failed");
                     }

                     CDELog.d(TAG, "load encryted epg data ok now");
                     endTime = System.currentTimeMillis();
                     CDELog.d(TAG, "load encrypted epg data cost: " + (endTime - beginTime) + " milliseconds");
                     realPayload = new byte[dataEntity.getDataLength() - nPaddingLen];
                     System.arraycopy(dataEntity.getData(), 0, realPayload, 0, dataEntity.getDataLength() - nPaddingLen);
                     if (!CDEUtils.getReleaseMode()) {
                         CDEUtils.bytesToFile(realPayload, Environment.getExternalStorageDirectory().getAbsolutePath() + "/kantv/", "download_tv_decrypt_debug.xml");
                         File sdcardPath = Environment.getExternalStorageDirectory();
                         CDELog.d(TAG, "sdcardPath name:" + sdcardPath.getName() + ",sdcardPath:" + sdcardPath.getPath());
                         CDELog.d(TAG, "sdcard free size:" + mKANTVDRM.ANDROID_JNI_GetDiskFreeSize(sdcardPath.getAbsolutePath()) + "MBytes");
                     }
                 } catch (Exception ex) {
                     CDELog.d(TAG, "load epg failed:" + ex.toString());
                     usingRemoteEPG = false;
                 }

             }
             mContentList = CDEUtils.EPGXmlParser.getContentDescriptors(realPayload);
             if ((mContentList == null) || (0 == mContentList.size())) {
                 CDELog.d(TAG, "failed to parse xml file:" + xmlLoaddedFileName);
                 usingRemoteEPG = false;
             } else {
                 CDELog.d(TAG, "ok to parse xml file:" + xmlLoaddedFileName);
                 CDELog.d(TAG, "epg items: " + mContentList.size());
             }
         } else {
             CDELog.d(TAG, "no remote epg file exist:" + xmlRemoteFile.getAbsolutePath());
         }

         if (!usingRemoteEPG) {
             CDELog.d(TAG, "load internal encrypted epg data");
             CDELog.d(TAG, "encryptedEPGFileName: " + encryptedEPGFileName);
             CDEAssetLoader.copyAssetFile(mContext, encryptedEPGFileName, CDEAssetLoader.getDataPath(mContext) + encryptedEPGFileName);
             CDELog.d(TAG, "encrypted asset path:" + CDEAssetLoader.getDataPath(mContext) + encryptedEPGFileName);//asset path:/data/data/com.cdeos.player/kantv.bin
             try {
                 File file = new File(CDEAssetLoader.getDataPath(mContext) + encryptedEPGFileName);
                 int fileLength = (int) file.length();
                 CDELog.d(TAG, "encrypted file len:" + fileLength);

                 if (fileLength < 2) {
                     CDELog.d(TAG, "shouldn't happen, pls check encrypted epg data");
                     return;
                 }


                 byte[] fileContent = new byte[fileLength];
                 FileInputStream in = new FileInputStream(file);
                 in.read(fileContent);
                 in.close();

                 byte[] paddingInfo = new byte[2];
                 paddingInfo[0] = 0;
                 paddingInfo[1] = 0;
                 System.arraycopy(fileContent, 0, paddingInfo, 0, 2);
                 CDELog.d(TAG, "padding len:" + Arrays.toString(paddingInfo));
                 CDELog.d(TAG, "padding len:" + (int) paddingInfo[0]);
                 CDELog.d(TAG, "padding len:" + (int) paddingInfo[1]);
                 nPaddingLen = (((paddingInfo[0] & 0xFF) << 8) | (paddingInfo[1] & 0xFF));
                 CDELog.d(TAG, "padding len:" + nPaddingLen);

                 byte[] payload = new byte[fileLength - 2];
                 System.arraycopy(fileContent, 2, payload, 0, fileLength - 2);

                 CDELog.d(TAG, "encrypted file length:" + fileLength);
                 mKANTVDRM.ANDROID_JNI_Decrypt(payload, fileLength - 2, dataEntity, KANTVJNIDecryptBuffer.getDirectBuffer());
                 CDELog.d(TAG, "decrypted data length:" + dataEntity.getDataLength());
                 if (dataEntity.getData() != null) {
                     CDELog.d(TAG, "decrypted ok");
                 } else {
                     CDELog.d(TAG, "decrypted failed");
                 }
             } catch (FileNotFoundException e) {
                 CDELog.d(TAG, "load encrypted epg data failed:" + e.getMessage());
                 return;
             } catch (IOException e) {
                 e.printStackTrace();
                 CDELog.d(TAG, "load encryted epg data failed:" + e.getMessage());
                 return;
             }
             CDELog.d(TAG, "load encryted epg data ok now");
             endTime = System.currentTimeMillis();
             CDELog.d(TAG, "load encrypted epg data cost: " + (endTime - beginTime) + " milliseconds");
             realPayload = new byte[dataEntity.getDataLength() - nPaddingLen];
             System.arraycopy(dataEntity.getData(), 0, realPayload, 0, dataEntity.getDataLength() - nPaddingLen);
             if (!CDEUtils.getReleaseMode()) {
                 CDEUtils.bytesToFile(realPayload, Environment.getExternalStorageDirectory().getAbsolutePath() + "/kantv/", "tv_decrypt_debug.xml");
                 File sdcardPath = Environment.getExternalStorageDirectory();
                 CDELog.d(TAG, "sdcardPath name:" + sdcardPath.getName() + ",sdcardPath:" + sdcardPath.getPath());
                 CDELog.d(TAG, "sdcard free size:" + mKANTVDRM.ANDROID_JNI_GetDiskFreeSize(sdcardPath.getAbsolutePath()));
             }

             mContentList = CDEUtils.EPGXmlParser.getContentDescriptors(realPayload);
             if ((mContentList == null) || (0 == mContentList.size())) {
                 CDELog.d(TAG, "xml parse failed");
                 return;
             }
             CDELog.d(TAG, "content counts:" + mContentList.size());
         }
         */

         //2025-01-29,customize TV program in non-encrypted tv.xml
         {
             CDEUtils.copyAssetFile(mContext, "tv.xml", CDEUtils.getDataPath(mContext) + "/tv.xml");
             File file = new File(CDEUtils.getDataPath(mContext), "tv.xml");
             beginTime = System.currentTimeMillis();
             int fileLength = (int) file.length();
             CDELog.j(TAG, "clear file len:" + fileLength);
             mData.clear();
             mContentList.clear();

             try {
                 byte[] fileContent = new byte[fileLength];
                 FileInputStream in = new FileInputStream(file);
                 in.read(fileContent);
                 in.close();
                 if (fileLength < 2) {
                     CDELog.j(TAG, "shouldn't happen, pls check tv.xml");
                     return;
                 }
                 mContentList = CDEUtils.EPGXmlParser.getContentDescriptors(fileContent);
             } catch (Exception ex) {
                 CDELog.j(TAG, "load tv xml failed:" + ex.toString());
                 CDELog.d(TAG, "shouldn't happen, pls check tv.xml");
                 return;
             }
         }

         if ((mContentList == null) || (0 == mContentList.size())) {
             CDELog.j(TAG, "shouldn't happen, pls check tv.xml");
             return;
         }
         CDELog.d(TAG, "epg items: " + mContentList.size());

         if (CDEMediaType.MEDIA_TV == mMediaType) {
             {
                 CDELog.d(TAG, "load tv logos from internal resources");
                 Resources res = mActivity.getResources();

                 if (true) {
                     Field field;
                     String resName = "test";
                     int resID = 0;
                     for (int index = 0; index < mContentList.size(); index++) {
                         resID = 0;
                         CDEContentDescriptor descriptor = mContentList.get(index);
                         String posterName = descriptor.getPoster();
                         if (posterName != null) {
                             posterName = posterName.trim();
                             resName = posterName.substring(0, posterName.indexOf('.'));
                         } else {
                             CDELog.d(TAG, "can't find poster name, pls check epg data");
                         }
                         String title = descriptor.getName();
                         title = title.trim();
                         String url = descriptor.getUrl();
                         url = url.trim();
                         //CDELog.d(TAG, "real resource name:" + resName);
                         //CDELog.d(TAG, "url:" + url );
                         //CDELog.d(TAG, "title:" + title);
                         //CDELog.d(TAG, "poster:" + posterName);
                         resID = res.getIdentifier(resName, "mipmap", mActivity.getPackageName());
                         if (resID == 0) {
                             CDELog.d(TAG, "can't find resource name:" + posterName);
                             resID = res.getIdentifier("test", "mipmap", mActivity.getPackageName());
                         }
                         mData.add(new KANTVMediaGridItem(resID, title));
                     }
                 } else {
                     mData.add(new KANTVMediaGridItem(R.mipmap.cgtn, "CGTN"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.cgtn_doc, "CGTN Doc"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.bloomberg_tv_emea, "Bloomberg TV"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.dw, "DW"));

                     mData.add(new KANTVMediaGridItem(R.mipmap.abc_news_live, "ABC News Live"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.fox_news_now, "Fox News Live Now"));

                     mData.add(new KANTVMediaGridItem(R.mipmap.cnn_us, "CNN"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.cbn_news, "CBN News"));

                     mData.add(new KANTVMediaGridItem(R.mipmap.fox_5_dc, " FOX 5 Washington DC"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.fox_5_newyork, "FOX 5 New York"));

                     mData.add(new KANTVMediaGridItem(R.mipmap.nbc_news_chicago, "NBC Chicago News"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.news_12_newyork, "New York News"));

                     mData.add(new KANTVMediaGridItem(R.mipmap.nasa_media, "NASA TV Media"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.nasa_uhd, "NASA TV Public"));

                     mData.add(new KANTVMediaGridItem(R.mipmap.bbc, "BBC sports"));
                     mData.add(new KANTVMediaGridItem(R.mipmap.cri, "China Radio"));
                 }
                 CDELog.d(TAG, "load internal resource counts:" + mData.size());
             }
         }
     }


     private void layoutUI(Activity activity) {
         int gridItemID = 0;
         if (CDEMediaType.MEDIA_TV == mMediaType) {
             gridItemID = R.layout.content_grid_item;
             gridItemImageID = R.id.img_icon;
             gridItemTextID = R.id.txt_icon;
         } else if (CDEMediaType.MEDIA_MOVIE == mMediaType) {
             if (CDEUtils.isRunningOnTV()) {
                 gridItemID = R.layout.content_grid_item_tv;
                 gridItemImageID = R.id.img_icon_tv;
                 gridItemTextID = R.id.txt_icon_tv;
             } else {
                 gridItemID = R.layout.content_grid_item;
                 gridItemImageID = R.id.img_icon;
                 gridItemTextID = R.id.txt_icon;
             }
         }

         mAdapter = new KANTVMediaGridAdapter<KANTVMediaGridItem>(mData, gridItemID, activity) {
             @Override
             public void bindView(KANTVMediaGridAdapter.ViewHolder holder, KANTVMediaGridItem obj) {
                 try {
                     {
                         CDELog.d(TAG, "load img from interl res");
                         holder.setImageResource(gridItemImageID, obj.getItemId());
                     }
                     //holder.setText(gridItemTextID, obj.getItemName());
                 } catch (Exception ex) {
                     CDELog.d(TAG, "error: " + ex.toString());
                 }
             }
         };
         layout.setAdapter(mAdapter);

         layout.setOnItemClickListener(new AdapterView.OnItemClickListener() {
             @Override
             public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                 if (!CDEUtils.isNetworkAvailable(mActivity)) {
                     Toast.makeText(getContext(), "pls check network connection", Toast.LENGTH_LONG).show();
                     return;
                 }
                 if (CDEUtils.getNetworkType() == CDEUtils.NETWORK_MOBILE) {
                     Toast.makeText(getContext(), "warning:you are using 3G/4G/5G network to watch online media", Toast.LENGTH_LONG).show();
                     //return;
                 }

                 CDEUtils.perfGetPlaybackBeginTime();
                 CDELog.d(TAG, "beginPlaybackTime: " + CDEUtils.perfGetPlaybackBeginTime());
                 KANTVMediaGridItem item = (KANTVMediaGridItem) parent.getItemAtPosition(position);
                 CDELog.d(TAG, "item position" + position);
                 CDEContentDescriptor descriptor = mContentList.get(position);
                 String name = descriptor.getName();
                 String url = descriptor.getUrl();
                 String drmScheme = descriptor.getDrmScheme();
                 String drmLicenseURL = descriptor.getLicenseURL();
                 boolean isLive = descriptor.getIsLive();
                 long playPos = 0;

                 CDELog.d(TAG, "name:" + name.trim());
                 CDELog.d(TAG, "url:" + url.trim());
                 CDELog.d(TAG, "mediaType: " + mMediaType.toString());
                 if (descriptor.getIsProtected()) {
                     CDELog.d(TAG, "drm scheme:" + drmScheme);
                     CDELog.d(TAG, "drm license url:" + drmLicenseURL);
                 } else {
                     CDELog.d(TAG, "clear content");
                 }
                 if (isLive) {
                     CDELog.d(TAG, "live content");
                 } else {
                     CDELog.d(TAG, "vod content");
                 }
                 CDELog.d(TAG, "Program： " + item.getItemName() + " ,url:" + url + " ,name:" + name.trim() + " ,mediaType:" + mMediaType.toString());


                 if (descriptor.getIsProtected()) {
                     CDELog.d(TAG, "drm scheme:" + drmScheme);
                     CDELog.d(TAG, "drm license url:" + drmLicenseURL);
                 } else {
                     CDELog.d(TAG, "clear content");
                 }
                 CDELog.d(TAG, "mediaType: " + mMediaType.toString());


                 initPlayback();

                 PlayerManagerActivity.launchPlayerOnline(getContext(), name, url, 0, 0, "");
             }
         });
     }

     public static native int kantv_anti_remove_rename_this_file();
 }
