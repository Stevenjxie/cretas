import React from 'react';
import { View, StyleSheet, StatusBar, Platform, ViewStyle, StyleProp } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { theme } from '../../theme';

interface ScreenWrapperProps {
  children: React.ReactNode;
  style?: StyleProp<ViewStyle>;
  withScrollView?: boolean;
  edges?: ('top' | 'right' | 'bottom' | 'left')[];
  backgroundColor?: string;
  testID?: string;
}

export const ScreenWrapper: React.FC<ScreenWrapperProps> = ({
  children,
  style,
  edges = ['top', 'left', 'right', 'bottom'],
  backgroundColor = theme.colors.background,
  testID,
}) => {
  return (
    <SafeAreaView 
      testID={testID}
      style={[
        styles.container, 
        { backgroundColor }, 
        style
      ]} 
      edges={edges}
    >
      <StatusBar
        barStyle="dark-content"
        backgroundColor="transparent"
        translucent={true}
      />
      <View style={styles.content}>
        {children}
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  content: {
    flex: 1,
    maxWidth: 500, // Constrain max width for larger screens/tablets
    width: '100%',
    alignSelf: 'center',
  },
});

