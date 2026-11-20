import { AuthProvider } from "@/components/auth/AuthProvider";
import { ProtectedGate } from "@/components/auth/ProtectedGate";

export default function ProtectedLayout({ children }) {
    return (
        <AuthProvider>
            <ProtectedGate>{children}</ProtectedGate>
        </AuthProvider>
    );
}
