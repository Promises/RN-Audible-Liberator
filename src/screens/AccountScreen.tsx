import React, { useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, StyleSheet, ScrollView } from 'react-native';
import { colors, spacing, typography } from '../styles/theme';

export default function AccountScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  const handleLogin = () => {
    // TODO: Implement Audible API authentication
    console.log('Login attempt:', email);
    setIsLoggedIn(true);
  };

  const handleLogout = () => {
    setEmail('');
    setPassword('');
    setIsLoggedIn(false);
  };

  if (isLoggedIn) {
    return (
      <View style={styles.container}>
        <View style={styles.content}>
          <Text style={styles.title}>Account</Text>

          <View style={styles.card}>
            <Text style={styles.label}>Logged in as</Text>
            <Text style={styles.value}>{email}</Text>
          </View>

          <View style={styles.card}>
            <Text style={styles.label}>Library Status</Text>
            <Text style={styles.value}>0 audiobooks</Text>
          </View>

          <View style={styles.card}>
            <Text style={styles.label}>Last Sync</Text>
            <Text style={styles.value}>Never</Text>
          </View>

          <TouchableOpacity style={styles.button} onPress={handleLogout}>
            <Text style={styles.buttonText}>Log Out</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  return (
    <ScrollView style={styles.container}>
      <View style={styles.content}>
        <Text style={styles.title}>Sign In to Audible</Text>
        <Text style={styles.subtitle}>
          Connect your Audible account to access your library
        </Text>

        <View style={styles.form}>
          <Text style={styles.inputLabel}>Email</Text>
          <TextInput
            style={styles.input}
            value={email}
            onChangeText={setEmail}
            placeholder="your@email.com"
            placeholderTextColor={colors.textSecondary}
            autoCapitalize="none"
            keyboardType="email-address"
          />

          <Text style={styles.inputLabel}>Password</Text>
          <TextInput
            style={styles.input}
            value={password}
            onChangeText={setPassword}
            placeholder="••••••••"
            placeholderTextColor={colors.textSecondary}
            secureTextEntry
          />

          <TouchableOpacity
            style={[styles.button, styles.loginButton]}
            onPress={handleLogin}
          >
            <Text style={styles.buttonText}>Sign In</Text>
          </TouchableOpacity>

          <Text style={styles.note}>
            Note: This app uses your Audible credentials to access your library.
            Credentials are stored locally and never shared.
          </Text>
        </View>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    padding: spacing.lg,
  },
  title: {
    ...typography.title,
    marginBottom: spacing.sm,
  },
  subtitle: {
    ...typography.caption,
    marginBottom: spacing.xl,
  },
  form: {
    gap: spacing.md,
  },
  inputLabel: {
    ...typography.body,
    marginBottom: spacing.xs,
  },
  input: {
    backgroundColor: colors.backgroundSecondary,
    borderRadius: 8,
    padding: spacing.md,
    color: colors.textPrimary,
    borderWidth: 1,
    borderColor: colors.border,
    fontSize: 16,
  },
  button: {
    backgroundColor: colors.backgroundSecondary,
    padding: spacing.md,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: colors.border,
    alignItems: 'center',
  },
  loginButton: {
    backgroundColor: colors.accentDim,
    borderColor: colors.accent,
    marginTop: spacing.md,
  },
  buttonText: {
    ...typography.body,
    fontWeight: '600',
  },
  note: {
    ...typography.caption,
    textAlign: 'center',
    marginTop: spacing.lg,
  },
  card: {
    backgroundColor: colors.backgroundSecondary,
    padding: spacing.md,
    borderRadius: 8,
    marginBottom: spacing.md,
    borderWidth: 1,
    borderColor: colors.border,
  },
  label: {
    ...typography.caption,
    marginBottom: spacing.xs,
  },
  value: {
    ...typography.body,
    fontWeight: '600',
  },
});
