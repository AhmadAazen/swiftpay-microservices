import http from 'k6/http';

export const options = {

    vus: 50,

    duration: '30s',
};

export default function () {

    const payload = JSON.stringify({

        transactionId: `txn-${__VU}-${__ITER}`,

        senderId: '11111111-1111-1111-1111-111111111111',

        receiverId: '22222222-2222-2222-2222-222222222222',

        amount: 10,

        currency: 'INR'
    });

    http.post(
        'http://localhost:8080/v1/payments',
        payload,
        {
            headers: {
                'Content-Type': 'application/json'
            }
        }
    );
}