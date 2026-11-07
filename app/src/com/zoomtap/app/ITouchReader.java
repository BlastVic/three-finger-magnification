package com.zoomtap.app;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written equivalent of the generated ITouchReader AIDL stub.
 * See app/aidl/com/zoomtap/app/ITouchReader.aidl for the interface contract.
 */
public interface ITouchReader extends IInterface {

    int start(String nodeHint, String language, ITouchCallback callback)
            throws RemoteException;

    void stop() throws RemoteException;

    int status() throws RemoteException;

    String probe() throws RemoteException;

    abstract class Stub extends Binder implements ITouchReader {

        static final String DESCRIPTOR = "com.zoomtap.app.ITouchReader";
        static final int TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1;
        static final int TRANSACTION_STATUS = IBinder.FIRST_CALL_TRANSACTION + 2;
        static final int TRANSACTION_PROBE = IBinder.FIRST_CALL_TRANSACTION + 3;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static ITouchReader asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof ITouchReader) {
                return (ITouchReader) local;
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
                case TRANSACTION_START: {
                    data.enforceInterface(DESCRIPTOR);
                    String nodeHint = data.readString();
                    String language = data.readString();
                    ITouchCallback callback =
                            ITouchCallback.Stub.asInterface(data.readStrongBinder());
                    int result = start(nodeHint, language, callback);
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_STOP: {
                    data.enforceInterface(DESCRIPTOR);
                    stop();
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_STATUS: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = status();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_PROBE: {
                    data.enforceInterface(DESCRIPTOR);
                    String result = probe();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements ITouchReader {
            private final IBinder mRemote;

            Proxy(IBinder remote) {
                mRemote = remote;
            }

            @Override
            public IBinder asBinder() {
                return mRemote;
            }

            @Override
            public int start(String nodeHint, String language, ITouchCallback callback)
                    throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(nodeHint);
                    data.writeString(language);
                    data.writeStrongBinder(callback == null ? null : callback.asBinder());
                    mRemote.transact(TRANSACTION_START, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void stop() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_STOP, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public int status() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_STATUS, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String probe() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_PROBE, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
