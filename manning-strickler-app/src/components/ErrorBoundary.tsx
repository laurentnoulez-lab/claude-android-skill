import React from 'react';
import { View, Text, StyleSheet } from 'react-native';

interface State {
  hasError: boolean;
  message?: string;
}

/**
 * Catches any runtime error in the subtree and shows a friendly message
 * instead of crashing / red-screening the whole app.
 */
export default class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  State
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, message: error?.message };
  }

  reset = () => this.setState({ hasError: false, message: undefined });

  render() {
    if (this.state.hasError) {
      return (
        <View style={styles.box}>
          <Text style={styles.title}>Une erreur est survenue</Text>
          <Text style={styles.msg}>
            Vérifiez les valeurs saisies. {this.state.message ? `(${this.state.message})` : ''}
          </Text>
          <Text style={styles.retry} onPress={this.reset}>
            Réessayer
          </Text>
        </View>
      );
    }
    return this.props.children;
  }
}

const styles = StyleSheet.create({
  box: { margin: 16, padding: 16, borderRadius: 12, backgroundColor: '#fbe4e6' },
  title: { fontSize: 16, fontWeight: '700', color: '#a01f2f', marginBottom: 6 },
  msg: { fontSize: 14, color: '#5a2a2f' },
  retry: { fontSize: 15, fontWeight: '700', color: '#1f4e79', marginTop: 12 },
});
