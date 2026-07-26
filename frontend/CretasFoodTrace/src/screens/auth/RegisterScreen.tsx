import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  Dimensions,
  StatusBar,
  Alert,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';

type IoniconsName = React.ComponentProps<typeof Ionicons>['name'];
import { useTranslation } from 'react-i18next';
import { useRegister } from '../../hooks/useRegister';
import { RegisterPhaseTwoRequest } from '../../types/auth';
import { NeoCard, NeoButton, ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import { logger } from '../../utils/logger';

// 创建Register专用logger
const registerLogger = logger.createContextLogger('Register');

const { width } = Dimensions.get('window');

interface RegisterScreenProps {
  navigation: any;
}

export const RegisterScreen: React.FC<RegisterScreenProps> = ({ navigation }) => {
  const { t } = useTranslation('auth');

  // Step 1: Phone Verification
  const [phoneNumber, setPhoneNumber] = useState('');

  // Step 2: Info Form
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [realName, setRealName] = useState('');

  // UI State
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const {
    verifyPhoneNumber,
    register,
    isLoading,
    error,
    currentStep,
    tempToken,
    invitation,
    clearError,
    resetForm
  } = useRegister();

  const handleVerifyPhone = async () => {
    if (!phoneNumber.trim()) {
      Alert.alert(t('register.alerts.hint'), t('register.alerts.phoneRequired'));
      return;
    }
    const result = await verifyPhoneNumber(phoneNumber);
    if (result.success && result.tempToken && result.invitation) {
      setRealName(result.invitation.invitedName || '');
      clearError();
    }
  };

  const handleRegister = async () => {
    if (!password.trim() || !realName.trim()) {
      Alert.alert(t('register.alerts.hint'), t('register.alerts.allFieldsRequired'));
      return;
    }

    if (password !== confirmPassword) {
      Alert.alert(t('register.alerts.hint'), t('register.alerts.passwordMismatch'));
      return;
    }

    if (!tempToken || !invitation) {
      Alert.alert(t('register.alerts.error'), t('register.alerts.tokenExpired'));
      return;
    }

    const registerRequest: RegisterPhaseTwoRequest = {
      tempToken,
      username: invitation.loginAccount,
      password: password.trim(),
      realName: realName.trim(),
      factoryId: invitation.factoryId,
    };

    try {
      const success = await register(registerRequest);
      if (success) {
        registerLogger.info('用户注册成功', {
          username: registerRequest.username,
          factoryId: registerRequest.factoryId,
        });
        navigation.navigate('LoginScreen');
      }
    } catch (err) {
      registerLogger.error('用户注册失败', err as Error, {
        username: registerRequest.username,
        factoryId: registerRequest.factoryId,
      });
    }
  };

  const handleBack = () => {
    if (currentStep === 'info') {
      resetForm();
    } else {
      navigation.goBack();
    }
  };

  const renderInput = (
    placeholder: string,
    value: string,
    onChange: (text: string) => void,
    icon: IoniconsName,
    secure = false,
    showSecure = false,
    toggleSecure?: () => void,
    keyboardType: 'default' | 'email-address' | 'phone-pad' = 'default'
  ) => (
    <View style={styles.inputContainer}>
      <Ionicons name={icon} size={20} color={theme.colors.onSurfaceVariant} style={styles.inputIcon} />
      <TextInput
        style={styles.input}
        placeholder={placeholder}
        placeholderTextColor={theme.colors.onSurfaceVariant}
        value={value}
        onChangeText={onChange}
        secureTextEntry={secure && !showSecure}
        keyboardType={keyboardType}
        autoCapitalize="none"
        editable={!isLoading}
      />
      {toggleSecure && (
        <TouchableOpacity style={styles.eyeIcon} onPress={toggleSecure}>
          <Ionicons
            name={showSecure ? "eye-off-outline" : "eye-outline"}
            size={20}
            color={theme.colors.onSurfaceVariant}
          />
        </TouchableOpacity>
      )}
    </View>
  );

  const renderPhoneVerification = () => (
    <View>
      <View style={styles.stepHeader}>
        <Text style={styles.stepTitle}>{t('register.step1of2')}</Text>
        <Text style={styles.stepDescription}>{t('register.phoneVerification')}</Text>
      </View>

      {renderInput(t('register.phonePlaceholder'), phoneNumber, setPhoneNumber, "phone-portrait-outline", false, false, undefined, "phone-pad")}

      <View style={styles.hintContainer}>
        <Ionicons name="information-circle-outline" size={16} color={theme.colors.primary} />
        <Text style={styles.hintText}>{t('register.hint')}</Text>
      </View>

      {error && (
        <View style={styles.errorContainer}>
          <Ionicons name="alert-circle-outline" size={20} color={theme.colors.error} />
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      <NeoButton
        variant="primary"
        onPress={handleVerifyPhone}
        loading={isLoading}
        disabled={isLoading || !phoneNumber.trim()}
        style={styles.nextButton}
      >
        {t('register.verifyPhone')}
      </NeoButton>
    </View>
  );

  const renderInfoForm = () => (
    <View>
      <View style={styles.stepHeader}>
        <Text style={styles.stepTitle}>{t('register.step2of2')}</Text>
        <Text style={styles.stepDescription}>{t('register.fillCompleteInfo')}</Text>
      </View>

      {invitation && (
        <View style={styles.invitationCard}>
          <View style={styles.invitationHeader}>
            <Ionicons name="shield-checkmark-outline" size={22} color={theme.colors.primary} />
            <Text style={styles.invitationTitle}>{t('register.invitationVerified')}</Text>
          </View>
          <View style={styles.invitationRow}>
            <Text style={styles.invitationLabel}>{t('register.loginAccount')}</Text>
            <Text style={styles.invitationValue}>{invitation.loginAccount}</Text>
          </View>
          <View style={styles.invitationRow}>
            <Text style={styles.invitationLabel}>{t('register.factoryId')}</Text>
            <Text style={styles.invitationValue}>{invitation.factoryId}</Text>
          </View>
          <View style={styles.invitationRow}>
            <Text style={styles.invitationLabel}>{t('register.invitedRole')}</Text>
            <Text style={styles.invitationValue}>
              {invitation.invitedRoleName || invitation.invitedRole || t('register.pendingActivation')}
            </Text>
          </View>
        </View>
      )}
      {renderInput(t('register.realNamePlaceholder'), realName, setRealName, "card-outline")}

      {renderInput(t('register.passwordPlaceholder'), password, setPassword, "lock-closed-outline", true, showPassword, () => setShowPassword(!showPassword))}
      {renderInput(t('register.confirmPasswordLabel'), confirmPassword, setConfirmPassword, "lock-closed-outline", true, showConfirmPassword, () => setShowConfirmPassword(!showConfirmPassword))}

      {error && (
        <View style={styles.errorContainer}>
          <Ionicons name="alert-circle-outline" size={20} color={theme.colors.error} />
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      <NeoButton
        variant="primary"
        onPress={handleRegister}
        loading={isLoading}
        disabled={isLoading}
        style={styles.nextButton}
      >
        {t('register.completeRegistration')}
      </NeoButton>
    </View>
  );

  return (
    <ScreenWrapper edges={['top', 'bottom']} backgroundColor={theme.colors.background}>
      <StatusBar translucent backgroundColor="transparent" barStyle="dark-content" />
      
      <View style={styles.header}>
        <TouchableOpacity onPress={handleBack} style={styles.backButton}>
          <Ionicons name="chevron-back" size={28} color={theme.colors.text} />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{t('register.headerTitle')}</Text>
        <View style={{ width: 44 }} />
      </View>

      <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
        <View style={styles.logoContainer}>
          <View style={styles.logoCircle}>
            <Ionicons name="person-add-outline" size={40} color={theme.colors.primary} />
          </View>
          <Text style={styles.title}>{t('register.createAccount')}</Text>
          <Text style={styles.subtitle}>{t('register.joinSystem')}</Text>
        </View>

        <NeoCard style={styles.formCard}>
          {currentStep === 'phone' ? renderPhoneVerification() : renderInfoForm()}
        </NeoCard>
      </ScrollView>
    </ScreenWrapper>
  );
};

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: theme.colors.background,
  },
  backButton: {
    padding: 8,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: theme.colors.text,
  },
  scrollContent: {
    padding: 24,
  },
  logoContainer: {
    alignItems: 'center',
    marginBottom: 32,
  },
  logoCircle: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: theme.colors.surface,
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 16,
    ...theme.custom.shadows.medium,
  },
  title: {
    fontSize: 24,
    fontWeight: '700',
    color: theme.colors.text,
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 14,
    color: theme.colors.textSecondary,
  },
  formCard: {
    padding: 24,
  },
  stepHeader: {
    marginBottom: 24,
    borderBottomWidth: 1,
    borderBottomColor: theme.colors.outline,
    paddingBottom: 16,
  },
  stepTitle: {
    fontSize: 12,
    fontWeight: '600',
    color: theme.colors.primary,
    marginBottom: 4,
  },
  stepDescription: {
    fontSize: 18,
    fontWeight: '700',
    color: theme.colors.text,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: theme.colors.surfaceVariant,
    borderRadius: theme.custom.borderRadius.s,
    paddingHorizontal: 16,
    height: 50,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  inputIcon: {
    marginRight: 12,
  },
  input: {
    flex: 1,
    fontSize: 15,
    color: theme.colors.text,
    height: '100%',
  },
  eyeIcon: {
    padding: 8,
  },
  hintContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: theme.colors.surfaceVariant, // Lighter shade
    borderRadius: theme.custom.borderRadius.s,
    padding: 12,
    marginBottom: 16,
  },
  hintText: {
    marginLeft: 8,
    color: theme.colors.textSecondary,
    fontSize: 13,
    flex: 1,
  },
  invitationCard: {
    backgroundColor: theme.colors.primaryContainer,
    borderRadius: 14,
    padding: 14,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: theme.colors.primary,
  },
  invitationHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  invitationTitle: {
    marginLeft: 8,
    fontSize: 15,
    fontWeight: '700',
    color: theme.colors.primary,
  },
  invitationRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    paddingVertical: 4,
  },
  invitationLabel: {
    color: theme.colors.onSurfaceVariant,
    fontSize: 13,
  },
  invitationValue: {
    flex: 1,
    marginLeft: 16,
    textAlign: 'right',
    color: theme.colors.onSurface,
    fontSize: 13,
    fontWeight: '600',
  },
  errorContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: theme.colors.errorContainer,
    borderRadius: theme.custom.borderRadius.s,
    padding: 12,
    marginBottom: 16,
  },
  errorText: {
    marginLeft: 8,
    color: theme.colors.error,
    fontSize: 13,
    flex: 1,
  },
  nextButton: {
    marginTop: 8,
  },
});

export default RegisterScreen;
