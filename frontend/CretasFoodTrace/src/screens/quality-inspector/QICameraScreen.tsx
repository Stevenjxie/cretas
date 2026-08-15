/**
 * 拍照质检页面
 * Quality Inspector - Camera Inspection Screen
 */

import React, { useState, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Image,
  ScrollView,
  Alert,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation, useRoute, RouteProp } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { CameraView, CameraType, useCameraPermissions } from 'expo-camera';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useTranslation } from 'react-i18next';

import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
type RouteProps = RouteProp<QualityInspectorStackParamList, 'QICamera'>;

export default function QICameraScreen() {
  const { t } = useTranslation('quality');
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteProps>();
  const insets = useSafeAreaInsets();
  const { batchId, batchNumber } = route.params;

  const [permission, requestPermission] = useCameraPermissions();
  const [facing, setFacing] = useState<CameraType>('back');
  const [flashMode, setFlashMode] = useState(false);
  const [photos, setPhotos] = useState<string[]>([]);
  const [previewUri, setPreviewUri] = useState<string | null>(null);

  const cameraRef = useRef<CameraView>(null);

  const takePicture = async () => {
    if (cameraRef.current) {
      try {
        const photo = await cameraRef.current.takePictureAsync({
          quality: 0.8,
          base64: false,
        });
        if (photo?.uri) {
          setPhotos(prev => [...prev, photo.uri]);
          setPreviewUri(photo.uri);
        }
      } catch (error) {
        console.error('拍照失败:', error);
        Alert.alert(t('camera.captureFailed'), t('camera.cancel'));
      }
    }
  };

  const toggleCameraFacing = () => {
    setFacing((current: 'back' | 'front') => (current === 'back' ? 'front' : 'back'));
  };

  const toggleFlash = () => {
    setFlashMode(!flashMode);
  };

  const deletePhoto = (index: number) => {
    setPhotos(prev => prev.filter((_, i) => i !== index));
    if (photos.length === 1) {
      setPreviewUri(null);
    } else if (previewUri === photos[index]) {
      setPreviewUri(photos[index === 0 ? 1 : 0] || null);
    }
  };

  const confirmPhotos = () => {
    // 🔴 这里原本只是 `navigation.goBack()` 加一句 TODO ——
    //    用户拍完照返回, **照片就地丢弃**, 表单永远收不到。
    //    这是「质检看不了图片」的第一环(另外两环: 后端不落库、读回时硬编空数组)。
    //    用 merge 把 uri 回传给表单, 由表单在提交拿到质检记录 id 之后上传成附件。
    navigation.navigate({
      name: 'QIForm',
      params: { capturedPhotos: photos },
      merge: true,
    } as never);
  };

  if (!permission) {
    return (
      <View style={styles.container}>
        <Text>{t('camera.loading')}</Text>
      </View>
    );
  }

  if (!permission.granted) {
    return (
      <View style={styles.permissionContainer}>
        <Ionicons name="camera-outline" size={64} color={QI_COLORS.disabled} />
        <Text style={styles.permissionTitle}>{t('camera.cameraPermission')}</Text>
        <Text style={styles.permissionText}>{t('camera.grantPermission')}</Text>
        <TouchableOpacity style={styles.permissionBtn} onPress={requestPermission}>
          <Text style={styles.permissionBtnText}>{t('camera.authorize')}</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* 相机视图 */}
      <CameraView
        ref={cameraRef}
        style={styles.camera}
        facing={facing}
        enableTorch={flashMode}
      >
        {/* 顶部控制栏 */}
        <View style={[styles.topBar, { paddingTop: insets.top }]}>
          <TouchableOpacity style={styles.topBtn} onPress={() => navigation.goBack()}>
            <Ionicons name="close" size={28} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.topTitle}>{t('camera.title')}</Text>
          <TouchableOpacity style={styles.topBtn} onPress={toggleFlash}>
            <Ionicons
              name={flashMode ? 'flash' : 'flash-off'}
              size={24}
              color="#fff"
            />
          </TouchableOpacity>
        </View>

        {/* 批次信息 */}
        <View style={styles.batchInfo}>
          <Text style={styles.batchText}>{batchNumber}</Text>
        </View>

        {/* 拍照指引 */}
        <View style={styles.guideFrame}>
          <View style={styles.guideCorner} />
          <View style={[styles.guideCorner, styles.guideCornerTR]} />
          <View style={[styles.guideCorner, styles.guideCornerBL]} />
          <View style={[styles.guideCorner, styles.guideCornerBR]} />
        </View>

        {/* 底部控制栏 */}
        <View style={[styles.bottomBar, { paddingBottom: insets.bottom + 20 }]}>
          {/* 照片预览 */}
          <View style={styles.thumbnailSection}>
            <ScrollView horizontal showsHorizontalScrollIndicator={false}>
              {photos.map((uri, index) => (
                <TouchableOpacity
                  key={index}
                  style={styles.thumbnail}
                  onPress={() => setPreviewUri(uri)}
                >
                  <Image source={{ uri }} style={styles.thumbnailImage} />
                  <TouchableOpacity
                    style={styles.deleteBtn}
                    onPress={() => deletePhoto(index)}
                  >
                    <Ionicons name="close-circle" size={20} color="#fff" />
                  </TouchableOpacity>
                </TouchableOpacity>
              ))}
            </ScrollView>
          </View>

          {/* 控制按钮 */}
          <View style={styles.controls}>
            <TouchableOpacity style={styles.controlBtn} onPress={toggleCameraFacing}>
              <Ionicons name="camera-reverse" size={28} color="#fff" />
            </TouchableOpacity>

            <TouchableOpacity style={styles.captureBtn} onPress={takePicture}>
              <View style={styles.captureBtnInner} />
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.controlBtn, photos.length === 0 && styles.controlBtnDisabled]}
              onPress={confirmPhotos}
              disabled={photos.length === 0}
            >
              <Ionicons name="checkmark-circle" size={28} color={photos.length > 0 ? '#fff' : 'rgba(255,255,255,0.5)'} />
              <Text style={[styles.controlText, photos.length === 0 && styles.controlTextDisabled]}>
                {t('camera.done')}
              </Text>
            </TouchableOpacity>
          </View>

          <Text style={styles.photoCount}>{t('camera.photoTaken', { count: photos.length })}</Text>
        </View>
      </CameraView>

      {/* 预览大图 */}
      {previewUri && (
        <TouchableOpacity
          style={styles.previewOverlay}
          activeOpacity={1}
          onPress={() => setPreviewUri(null)}
        >
          <Image source={{ uri: previewUri }} style={styles.previewImage} resizeMode="contain" />
          <TouchableOpacity style={styles.closePreview} onPress={() => setPreviewUri(null)}>
            <Ionicons name="close" size={24} color="#fff" />
          </TouchableOpacity>
        </TouchableOpacity>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  camera: {
    flex: 1,
  },

  // 权限页面
  permissionContainer: {
    flex: 1,
    backgroundColor: QI_COLORS.background,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  permissionTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: QI_COLORS.text,
    marginTop: 16,
    marginBottom: 8,
  },
  permissionText: {
    fontSize: 14,
    color: QI_COLORS.textSecondary,
    marginBottom: 24,
  },
  permissionBtn: {
    backgroundColor: QI_COLORS.primary,
    paddingHorizontal: 32,
    paddingVertical: 14,
    borderRadius: 12,
  },
  permissionBtnText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },

  // 顶部栏
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingBottom: 12,
    backgroundColor: 'rgba(0,0,0,0.3)',
  },
  topBtn: {
    width: 44,
    height: 44,
    justifyContent: 'center',
    alignItems: 'center',
  },
  topTitle: {
    color: '#fff',
    fontSize: 17,
    fontWeight: '600',
  },

  // 批次信息
  batchInfo: {
    alignSelf: 'center',
    backgroundColor: 'rgba(0,0,0,0.5)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    marginTop: 12,
  },
  batchText: {
    color: '#fff',
    fontSize: 14,
  },

  // 拍照指引框
  guideFrame: {
    position: 'absolute',
    top: '25%',
    left: '10%',
    right: '10%',
    bottom: '35%',
  },
  guideCorner: {
    position: 'absolute',
    width: 24,
    height: 24,
    borderColor: '#fff',
    borderTopWidth: 2,
    borderLeftWidth: 2,
    top: 0,
    left: 0,
  },
  guideCornerTR: {
    left: undefined,
    right: 0,
    borderLeftWidth: 0,
    borderRightWidth: 2,
  },
  guideCornerBL: {
    top: undefined,
    bottom: 0,
    borderTopWidth: 0,
    borderBottomWidth: 2,
  },
  guideCornerBR: {
    top: undefined,
    left: undefined,
    bottom: 0,
    right: 0,
    borderTopWidth: 0,
    borderLeftWidth: 0,
    borderBottomWidth: 2,
    borderRightWidth: 2,
  },

  // 底部栏
  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingTop: 16,
  },

  // 缩略图
  thumbnailSection: {
    height: 60,
    paddingHorizontal: 16,
    marginBottom: 16,
  },
  thumbnail: {
    width: 52,
    height: 52,
    marginRight: 8,
    borderRadius: 8,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.5)',
  },
  thumbnailImage: {
    width: '100%',
    height: '100%',
  },
  deleteBtn: {
    position: 'absolute',
    top: -6,
    right: -6,
  },

  // 控制按钮
  controls: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 40,
    marginBottom: 16,
  },
  controlBtn: {
    alignItems: 'center',
  },
  controlBtnDisabled: {
    opacity: 0.5,
  },
  controlText: {
    color: '#fff',
    fontSize: 12,
    marginTop: 4,
  },
  controlTextDisabled: {
    color: 'rgba(255,255,255,0.5)',
  },
  captureBtn: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 4,
    borderColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
  },
  captureBtnInner: {
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: '#fff',
  },

  photoCount: {
    color: 'rgba(255,255,255,0.7)',
    fontSize: 13,
    textAlign: 'center',
  },

  // 预览大图
  previewOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.95)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  previewImage: {
    width: '100%',
    height: '80%',
  },
  closePreview: {
    position: 'absolute',
    top: 50,
    right: 20,
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
});
