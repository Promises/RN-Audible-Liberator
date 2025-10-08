import { StatusBar } from 'expo-status-bar';
import { useColorScheme } from 'react-native';
import AppNavigator from './src/navigation/AppNavigator';

export default function App() {
  const colorScheme = useColorScheme();

  return (
    <>
      <AppNavigator />
      <StatusBar style={colorScheme === 'dark' ? 'light' : 'dark'} />
    </>
  );
}
