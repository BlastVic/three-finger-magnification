package com.zoomtap.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written equivalent of the generated ITouchCallback AIDL stub.
 * Kept as source so the manual build chain does not depend on aidl.exe.
 * See app/aidl/com/zoomtap/app/ITouchCallback.aidl for the interface contract.
 */
public interface ITouchCallback extends IInterface {

    void onTripleDoubleTap(long id, float normalizedX, float normalizedY, int peak)
            throws RemoteException;

    void onLog(String message) throws RemoteException;

    abstract class Stub extends Binder implements ITouchCallback {

        static final String DESCRIPTOR = "com.zoomtap.app.ITouchCallback";
        static final int TRANSACTION_TRIPLE = IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_LOG = IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ITouchCallback asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof ITouchCallback) {
                return (ITouchCallback) local;
            }
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            switch (code) {
                case TRANSACTION_TRIPLE: {
                    data.enforceInterface(DESCRIPTOR);
                    long id = data.readLong();
                    float nx = data.readFloat();
                    float ny = data.readFloat();
                    int peak = data.readInt();
                    onTripleDoubleTap(id, nx, ny, peak);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_LOG: {
                    data.enforceInterface(DESCRIPTOR);
                    String message = data.readString();
                    onLog(message);
                    reply.writeNoException();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements ITouchCallback {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public void onTripleDoubleTap(long id, float nx, float ny, int peak)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(id);
                    data.writeFloat(nx);
                    data.writeFloat(ny);
                    data.writeInt(peak);
                    mRemote.transact(TRANSACTION_TRIPLE, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void onLog(String message) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(message);
                    mRemote.transact(TRANSACTION_LOG, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
